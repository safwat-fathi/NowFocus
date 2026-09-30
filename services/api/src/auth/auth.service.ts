import { createHash, randomBytes, randomUUID, timingSafeEqual } from 'node:crypto';
import { Injectable } from '@nestjs/common';
import { JwtService } from '@nestjs/jwt';
import { Subject } from 'rxjs';
import { InjectRepository } from '@nestjs/typeorm';
import { IsNull, Repository } from 'typeorm';
import { Clock } from '../clock.js';
import { Device, User } from '../db/entities.js';
import { fail } from '../errors.js';
import type { CredentialsDto, DeviceInfoDto } from './auth.dto.js';
import { hashPassword, verifyPassword } from './password.js';

export const ACCESS_TTL_S = 15 * 60;
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
  private dummyHash?: Promise<string>;
  /** Emits a deviceId once its access has been revoked (the realtime gateway drops its socket). */
  readonly revoked = new Subject<string>();

  constructor(
    @InjectRepository(User) private users: Repository<User>,
    @InjectRepository(Device) private devices: Repository<Device>,
    private jwt: JwtService,
    private clock: Clock,
  ) {}

  async register(dto: CredentialsDto) {
    const email = dto.email.trim().toLowerCase();
    const user = this.users.create({ id: randomUUID(), email, passwordHash: await hashPassword(dto.password) });
    try {
      await this.users.insert(user);
    } catch (e: any) {
      if (e?.driverError?.code === '23505') throw fail(409, 'email_taken', 'An account with this email already exists');
      throw e;
    }
    return this.openSession(user, dto.device);
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
    return {
      user: { id: user.id, email: user.email },
      device: { id: device.id, name: device.name, platform: device.platform },
      accessToken: await this.signAccess(user.id, device.id),
      refreshToken: `${device.id}.${refresh.secret}`,
      expiresIn: ACCESS_TTL_S,
    };
  }
}
