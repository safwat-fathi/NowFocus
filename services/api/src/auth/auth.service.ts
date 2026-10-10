import { createHash, randomBytes, randomUUID, timingSafeEqual } from 'node:crypto';
import { Injectable, Logger } from '@nestjs/common';
import { JwtService } from '@nestjs/jwt';
import { Subject } from 'rxjs';
import { InjectRepository } from '@nestjs/typeorm';
import { IsNull, Repository } from 'typeorm';
import { Clock } from '../clock.js';
import { Device, PendingSignup, User } from '../db/entities.js';
import { Mailer } from '../mail.js';
import { alreadyRegisteredEmail, verifyEmail } from './auth-emails.js';
import { fail } from '../errors.js';
import type { CredentialsDto, DeviceInfoDto } from './auth.dto.js';
import { hashPassword, verifyPassword } from './password.js';

export const ACCESS_TTL_S = 15 * 60;
const VERIFY_TTL_MS = 24 * 3600 * 1000;
const RESEND_AFTER_MS = 60 * 1000;
const MAIL_LIMIT = 3;
const REFRESH_TTL_MS = 60 * 24 * 3600 * 1000;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export interface AuthContext {
  userId: string;
  deviceId: string;
}

const sha256 = (s: string) => createHash('sha256').update(s).digest('hex');
const invalidCredentials = () => fail(401, 'invalid_credentials', 'Invalid email or password');
const invalidRefresh = () => fail(401, 'invalid_refresh_token', 'Refresh token is invalid, expired or revoked');

@Injectable()
export class AuthService {
  private readonly logger = new Logger(AuthService.name);
  private readonly jobs = new Set<Promise<unknown>>();
  private dummyHash?: Promise<string>;
  /** Emits a deviceId once its access has been revoked (the realtime gateway drops its socket). */
  readonly revoked = new Subject<string>();

  constructor(
    @InjectRepository(User) private users: Repository<User>,
    @InjectRepository(Device) private devices: Repository<Device>,
    @InjectRepository(PendingSignup) private pending: Repository<PendingSignup>,
    private mailer: Mailer,
    private jwt: JwtService,
    private clock: Clock,
  ) {}

  /**
   * Same answer for a new and a taken email, so the endpoint can't be used to probe who has an account. Everything
   * past the password hash runs after the response, so response time can't tell the two apart either. The account is
   * created by `verify`, once the owner of the inbox confirms.
   */
  async register(dto: CredentialsDto) {
    const email = dto.email.trim().toLowerCase();
    const passwordHash = await hashPassword(dto.password);
    const job = this.startSignup(email, passwordHash).catch((e) => this.logger.error(`sign-up for ${email} failed: ${e}`));
    this.jobs.add(job);
    void job.finally(() => this.jobs.delete(job));
    return { status: 'verification_sent' as const };
  }

  /** Resolves when every sign-up started so far has finished its background work (tests wait on this). */
  settled() {
    return Promise.all([...this.jobs]);
  }

  private async startSignup(email: string, passwordHash: string) {
    if (!(await this.mailAllowed(email))) return;
    if (await this.users.existsBy({ email })) return this.send(email, alreadyRegisteredEmail());
    const now = this.clock.now();
    const row = await this.pending.findOneBy({ email });
    if (row && now.getTime() - row.createdAt.getTime() < RESEND_AFTER_MS) return;
    const token = randomBytes(32).toString('base64url');
    await this.pending.upsert(
      { email, passwordHash, tokenHash: sha256(token), expiresAt: new Date(now.getTime() + VERIFY_TTL_MS), createdAt: now },
      ['email'],
    );
    await this.send(email, verifyEmail(`https://api.nowfocus.online/v1/auth/verify?token=${token}`));
  }

  /** At most MAIL_LIMIT sign-up mails per address per hour, however many IPs ask: stops flooding someone's inbox. */
  private async mailAllowed(email: string) {
    const now = this.clock.now();
    const [{ sent }] = await this.users.query(
      `insert into signup_mail_limits (email, window_start, sent) values ($1, $2, 1)
       on conflict (email) do update set
         sent = case when signup_mail_limits.window_start <= $2::timestamptz - interval '1 hour' then 1 else signup_mail_limits.sent + 1 end,
         window_start = case when signup_mail_limits.window_start <= $2::timestamptz - interval '1 hour' then $2 else signup_mail_limits.window_start end
       returning sent`,
      [email, now],
    );
    return sent <= MAIL_LIMIT;
  }

  /**
   * Turns a confirmed sign-up into an account. The password must be the one typed at sign-up: a link someone else
   * triggered for your address is useless to them unless you also type their password.
   */
  async verify(token: string, password: string) {
    const row = await this.pending.findOneBy({ tokenHash: sha256(token) });
    if (!row || row.expiresAt <= this.clock.now()) throw fail(404, 'not_found', 'This link is invalid or has expired');
    if (!(await verifyPassword(password, row.passwordHash))) throw fail(403, 'wrong_password', 'Password is incorrect');
    try {
      await this.users.insert({ id: randomUUID(), email: row.email, passwordHash: row.passwordHash });
    } catch (e: any) {
      if (e?.driverError?.code !== '23505') throw e; // already created (link clicked twice, or a race): same outcome
    }
    await this.pending.delete({ email: row.email });
  }

  private send(to: string, m: { subject: string; html: string }) {
    return this.mailer.send(to, m.subject, m.html);
  }

  async login(dto: CredentialsDto) {
    const user = await this.users
      .createQueryBuilder('u')
      .where('lower(u.email) = :email', { email: dto.email.trim().toLowerCase() })
      .getOne();
    // Always run one scrypt so an unknown email costs the same as a wrong password.
    this.dummyHash ??= hashPassword('dummy-password');
    const ok = await verifyPassword(dto.password, user?.passwordHash ?? (await this.dummyHash));
    if (!user || !ok) throw invalidCredentials();
    return this.openSession(user, dto.device);
  }

  async refresh(token: string) {
    const [deviceId, secret] = token.split('.', 2);
    if (!deviceId || !secret || !UUID.test(deviceId)) throw invalidRefresh();
    const device = await this.devices.findOneBy({ id: deviceId, revokedAt: IsNull() });
    const now = this.clock.now();
    if (!device?.refreshHash || !device.refreshExpiresAt || device.refreshExpiresAt <= now) throw invalidRefresh();
    const given = Buffer.from(sha256(secret));
    const stored = Buffer.from(device.refreshHash);
    if (given.length !== stored.length || !timingSafeEqual(given, stored)) throw invalidRefresh();

    const next = this.newRefreshSecret();
    // Conditional on the old hash: two concurrent refreshes with one token can't both win.
    const res = await this.devices.update(
      { id: device.id, refreshHash: device.refreshHash, revokedAt: IsNull() },
      { refreshHash: next.hash, refreshExpiresAt: new Date(now.getTime() + REFRESH_TTL_MS), lastSeenAt: now },
    );
    if (res.affected !== 1) throw invalidRefresh();
    return {
      accessToken: await this.signAccess(device.userId, device.id),
      refreshToken: `${device.id}.${next.secret}`,
      expiresIn: ACCESS_TTL_S,
    };
  }

  /** Verifies a bearer token AND that its device is still live — the revocation check. */
  async authenticate(header: string | undefined): Promise<AuthContext> {
    const token = header?.startsWith('Bearer ') ? header.slice(7) : undefined;
    if (!token) throw fail(401, 'unauthorized', 'Missing bearer token');
    let claims: { sub: string; did: string };
    try {
      claims = await this.jwt.verifyAsync(token);
    } catch {
      throw fail(401, 'unauthorized', 'Invalid or expired token');
    }
    const device = await this.devices.findOneBy({ id: claims.did, userId: claims.sub, revokedAt: IsNull() });
    if (!device) throw fail(401, 'unauthorized', 'Device has been revoked');
    return { userId: claims.sub, deviceId: device.id };
  }

  async revoke(userId: string, deviceId: string) {
    if (!UUID.test(deviceId)) throw fail(404, 'device_not_found', 'Device not found');
    const res = await this.devices.update({ id: deviceId, userId, revokedAt: IsNull() }, { revokedAt: this.clock.now(), refreshHash: null, refreshExpiresAt: null });
    if (res.affected !== 1) throw fail(404, 'device_not_found', 'Device not found');
    this.revoked.next(deviceId);
  }

  async me(userId: string) {
    const u = await this.users.findOneByOrFail({ id: userId });
    return { id: u.id, email: u.email, createdAt: u.createdAt };
  }

  /**
   * Permanently deletes the account and everything synced under it (FK cascade). Local data on the user's
   * devices is untouched — clients keep enforcing from it. Every live socket is told and closed.
   */
  async deleteAccount(userId: string, password: string) {
    const user = await this.users.findOneByOrFail({ id: userId });
    // 403, not 401: a 401 makes clients try a token refresh, which can't fix a wrong password.
    if (!(await verifyPassword(password, user.passwordHash))) throw fail(403, 'wrong_password', 'Password is incorrect');
    const live = await this.devices.find({ where: { userId, revokedAt: IsNull() }, select: { id: true } });
    await this.users.delete({ id: userId });
    for (const d of live) this.revoked.next(d.id);
  }

  async listDevices(userId: string, currentDeviceId: string) {
    const rows = await this.devices.find({ where: { userId }, order: { createdAt: 'ASC' } });
    return rows.map((d) => ({ id: d.id, name: d.name, platform: d.platform, createdAt: d.createdAt, lastSeenAt: d.lastSeenAt, revokedAt: d.revokedAt, current: d.id === currentDeviceId }));
  }

  private newRefreshSecret() {
    const secret = randomBytes(32).toString('base64url');
    return { secret, hash: sha256(secret) };
  }

  private signAccess(userId: string, deviceId: string) {
    return this.jwt.signAsync({ sub: userId, did: deviceId }, { expiresIn: ACCESS_TTL_S });
  }

  private async openSession(user: User, info: DeviceInfoDto) {
    const now = this.clock.now();
    const refresh = this.newRefreshSecret();
    const device = this.devices.create({
      id: randomUUID(), userId: user.id, name: info.name, platform: info.platform,
      refreshHash: refresh.hash, refreshExpiresAt: new Date(now.getTime() + REFRESH_TTL_MS), lastSeenAt: now, revokedAt: null,
    });
    await this.devices.insert(device);
    // Same machine signing in again (reinstall, lost token, re-login): retire its previous entries so the
    // device list doesn't fill up with duplicates.
    const stale = await this.devices.find({
      where: { userId: user.id, name: info.name, platform: info.platform, revokedAt: IsNull() },
      select: { id: true },
    });
    for (const d of stale.filter((d) => d.id !== device.id)) {
      await this.devices.update({ id: d.id }, { revokedAt: now, refreshHash: null, refreshExpiresAt: null });
      this.revoked.next(d.id);
    }
    return {
      user: { id: user.id, email: user.email },
      device: { id: device.id, name: device.name, platform: device.platform },
      accessToken: await this.signAccess(user.id, device.id),
      refreshToken: `${device.id}.${refresh.secret}`,
      expiresIn: ACCESS_TTL_S,
    };
  }
}
