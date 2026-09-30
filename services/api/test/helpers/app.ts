import { Test } from '@nestjs/testing';
import type { INestApplication } from '@nestjs/common';
import { ThrottlerGuard } from '@nestjs/throttler';
import request from 'supertest';
import { DataSource } from 'typeorm';
import { AppModule } from '../../src/app.module.js';
import { Clock } from '../../src/clock.js';
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

export interface TestApp {
  app: INestApplication;
  clock: FakeClock;
  http: ReturnType<typeof request>;
  url: string;
  close: () => Promise<void>;
}

/** Boots the real app against the test DB (wiped per call). Throttling is off unless asked for. */
export async function createTestApp(opts: { throttle?: boolean } = {}): Promise<TestApp> {
  const clock = new FakeClock();
  const builder = Test.createTestingModule({ imports: [AppModule] }).overrideProvider(Clock).useValue(clock);
  if (!opts.throttle) builder.overrideGuard(ThrottlerGuard).useValue({ canActivate: () => true });
  const app = (await builder.compile()).createNestApplication();
  configureApp(app);
  await app.init();
  await app.listen(0);
  await app.get(DataSource).query('truncate users cascade');
  const url = (await app.getUrl()).replace('[::1]', 'localhost');
  return { app, clock, http: request(app.getHttpServer()), url, close: () => app.close() };
}

export interface Session {
  accessToken: string;
  refreshToken: string;
  device: { id: string; name: string; platform: string };
  user: { id: string; email: string };
}

export const bearer = (s: Pick<Session, 'accessToken'>) => ({ Authorization: `Bearer ${s.accessToken}` });

export async function signUp(t: TestApp, email: string, platform = 'macos', password = 'pw-pw-pw-pw'): Promise<Session> {
  const res = await t.http.post('/v1/auth/register').send({ email, password, device: { name: `${platform} box`, platform } });
  expect(res.status).toBe(201);
  return res.body;
}

export async function logIn(t: TestApp, email: string, platform = 'android', password = 'pw-pw-pw-pw'): Promise<Session> {
  const res = await t.http.post('/v1/auth/login').send({ email, password, device: { name: `${platform} box`, platform } });
  expect(res.status).toBe(200);
  return res.body;
}
