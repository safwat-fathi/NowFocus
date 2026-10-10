import { Test } from '@nestjs/testing';
import type { INestApplication } from '@nestjs/common';
import { ThrottlerGuard } from '@nestjs/throttler';
import request from 'supertest';
import { DataSource } from 'typeorm';
import { AppModule } from '../../src/app.module.js';
import { AuthService } from '../../src/auth/auth.service.js';
import { Clock } from '../../src/clock.js';
import { Mailer } from '../../src/mail.js';
import { configureApp } from '../../src/setup.js';

export class FakeClock extends Clock {
  private t = new Date();
  now() {
    return new Date(this.t);
  }
  advance(ms: number) {
    this.t = new Date(this.t.getTime() + ms);
  }
}

/** Captures outgoing auth mail instead of sending it. */
export class FakeMailer extends Mailer {
  sent: { to: string; subject: string; html: string }[] = [];
  constructor() {
    super({} as any);
  }
  async send(to: string, subject: string, html: string) {
    this.sent.push({ to, subject, html });
  }
}

export interface TestApp {
  mailer: FakeMailer;
  app: INestApplication;
  clock: FakeClock;
  http: ReturnType<typeof request>;
  url: string;
  close: () => Promise<void>;
}

/** Boots the real app against the test DB (wiped per call). Throttling is off unless asked for. */
export async function createTestApp(opts: { throttle?: boolean } = {}): Promise<TestApp> {
  const clock = new FakeClock();
  const mailer = new FakeMailer();
  const builder = Test.createTestingModule({ imports: [AppModule] }).overrideProvider(Clock).useValue(clock).overrideProvider(Mailer).useValue(mailer);
  if (!opts.throttle) builder.overrideGuard(ThrottlerGuard).useValue({ canActivate: () => true });
  const app = (await builder.compile()).createNestApplication();
  configureApp(app);
  await app.init();
  await app.listen(0);
  await app.get(DataSource).query('truncate users, pending_signups, signup_mail_limits cascade');
  const url = (await app.getUrl()).replace('[::1]', 'localhost');
  return { app, clock, mailer, http: request(app.getHttpServer()), url, close: () => app.close() };
}

export interface Session {
  accessToken: string;
  refreshToken: string;
  device: { id: string; name: string; platform: string };
  user: { id: string; email: string };
}

export const bearer = (s: Pick<Session, 'accessToken'>) => ({ Authorization: `Bearer ${s.accessToken}` });

/** Registers, then waits for the background work (DB rows, mail) that runs after the response. */
export const register = async (t: TestApp, email: string, platform = 'macos', password = 'pw-pw-pw-pw'): Promise<request.Response> => {
  const res = await t.http.post('/v1/auth/register').send({ email, password, device: { name: `${platform} box`, platform } });
  await t.app.get(AuthService).settled();
  return res;
};

/** The confirm page's form post. */
export const confirm = (t: TestApp, token: string | undefined, password = 'pw-pw-pw-pw') =>
  t.http.post(`/v1/auth/verify?token=${token}`).type('form').send({ password });

/** The token in the last verification mail sent to `email`. */
export const verifyToken = (t: TestApp, email: string) => {
  const m = [...t.mailer.sent].reverse().find((x) => x.to === email.toLowerCase() && x.html.includes('/v1/auth/verify?token='));
  return m?.html.match(/token=([\w-]+)/)?.[1];
};

/** Register, confirm the emailed link, then sign in: what a user does across the app and their inbox. */
export async function signUp(t: TestApp, email: string, platform = 'macos', password = 'pw-pw-pw-pw'): Promise<Session> {
  expect((await register(t, email, platform, password)).status).toBe(202);
  const token = verifyToken(t, email);
  expect((await confirm(t, token, password)).text).toMatch(/Email confirmed/);
  return logIn(t, email, platform, password);
}

export async function logIn(t: TestApp, email: string, platform = 'android', password = 'pw-pw-pw-pw'): Promise<Session> {
  const res = await t.http.post('/v1/auth/login').send({ email, password, device: { name: `${platform} box`, platform } });
  expect(res.status).toBe(200);
  return res.body;
}
