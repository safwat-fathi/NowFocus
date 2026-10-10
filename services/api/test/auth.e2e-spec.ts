import { bearer, createTestApp, logIn, register, signUp, verifyToken, type TestApp } from './helpers/app.js';

describe('auth + devices', () => {
  let t: TestApp;
  beforeEach(async () => {
    t = await createTestApp();
  });
  afterEach(() => t.close());

  it('register answers identically for a new and a taken email, and only verify creates the account', async () => {
    const fresh = await register(t, 'Ann@Example.com');
    expect(fresh.status).toBe(202);
    expect(fresh.body).toEqual({ status: 'verification_sent' });
    expect((await t.http.post('/v1/auth/login').send({ email: 'ann@example.com', password: 'pw-pw-pw-pw', device: { name: 'x', platform: 'macos' } })).status).toBe(401);

    const token = verifyToken(t, 'ann@example.com')!;
    expect((await t.http.get(`/v1/auth/verify?token=${token}`)).status).toBe(200); // a prefetch must not create it
    expect((await t.http.post('/v1/auth/login').send({ email: 'ann@example.com', password: 'pw-pw-pw-pw', device: { name: 'x', platform: 'macos' } })).status).toBe(401);
    expect((await t.http.post(`/v1/auth/verify?token=${token}`)).status).toBe(200);
    const s = await logIn(t, 'ann@example.com', 'macos');
    expect(s.user.email).toBe('ann@example.com');
    expect((await t.http.post(`/v1/auth/verify?token=${token}`)).status).toBe(404); // single use

    const dup = await register(t, 'ANN@example.com', 'windows');
    expect(dup.status).toBe(fresh.status);
    expect(dup.body).toEqual(fresh.body);
    expect(t.mailer.sent.at(-1)!.subject).toMatch(/already have/);
  });

  it('an expired verification link is rejected', async () => {
    await register(t, 'ann@example.com');
    const token = verifyToken(t, 'ann@example.com');
    t.clock.advance(25 * 3600 * 1000);
    expect((await t.http.post(`/v1/auth/verify?token=${token}`)).status).toBe(404);
  });

  it('a second register within a minute sends no second mail, and a later one replaces the link', async () => {
    await register(t, 'ann@example.com');
    const first = verifyToken(t, 'ann@example.com');
    await register(t, 'ann@example.com');
    expect(t.mailer.sent).toHaveLength(1);
    t.clock.advance(61_000);
    await register(t, 'ann@example.com');
    expect(t.mailer.sent).toHaveLength(2);
    expect(verifyToken(t, 'ann@example.com')).not.toBe(first);
  });

  it('login gives a wrong password and an unknown email the same 401, and registers a new device', async () => {
    await signUp(t, 'ann@example.com');
    const body = (email: string, password: string) => ({ email, password, device: { name: 'p', platform: 'android' } });
    const bad = await t.http.post('/v1/auth/login').send(body('ann@example.com', 'nope-nope-nope'));
    const unknown = await t.http.post('/v1/auth/login').send(body('who@example.com', 'nope-nope-nope'));
    expect(bad.status).toBe(401);
    expect(unknown.status).toBe(401);
    expect(bad.body).toEqual(unknown.body);

    const b = await logIn(t, 'ann@example.com');
    const list = await t.http.get('/v1/devices').set(bearer(b));
    expect(list.body.map((d: any) => d.platform).sort()).toEqual(['android', 'macos']);
    expect(list.body.find((d: any) => d.id === b.device.id).current).toBe(true);
  });

  it('protects routes: no token or a junk token is 401', async () => {
    expect((await t.http.get('/v1/devices')).status).toBe(401);
    expect((await t.http.get('/v1/devices').set('Authorization', 'Bearer junk')).status).toBe(401);
  });

  it('refresh rotates the token: the old one dies, the new one works', async () => {
    const s = await signUp(t, 'ann@example.com');
    const r1 = await t.http.post('/v1/auth/refresh').send({ refreshToken: s.refreshToken });
    expect(r1.status).toBe(200);
    expect(r1.body.refreshToken).not.toBe(s.refreshToken);
    expect((await t.http.post('/v1/auth/refresh').send({ refreshToken: s.refreshToken })).status).toBe(401);
    expect((await t.http.post('/v1/auth/refresh').send({ refreshToken: r1.body.refreshToken })).status).toBe(200);
  });

  it('refresh fails once the 60-day window has passed', async () => {
    const s = await signUp(t, 'ann@example.com');
    t.clock.advance(61 * 24 * 3600 * 1000);
    expect((await t.http.post('/v1/auth/refresh').send({ refreshToken: s.refreshToken })).status).toBe(401);
  });

  it('revoking a device kills its access and refresh tokens immediately', async () => {
    const a = await signUp(t, 'ann@example.com');
    const b = await logIn(t, 'ann@example.com');
    expect((await t.http.get('/v1/devices').set(bearer(b))).status).toBe(200);
    expect((await t.http.delete(`/v1/devices/${b.device.id}`).set(bearer(a))).status).toBe(204);
    expect((await t.http.get('/v1/devices').set(bearer(b))).status).toBe(401);
    expect((await t.http.post('/v1/auth/refresh').send({ refreshToken: b.refreshToken })).status).toBe(401);
    expect((await t.http.get('/v1/devices').set(bearer(a))).status).toBe(200);
  });

  it('logout revokes only the calling device', async () => {
    const a = await signUp(t, 'ann@example.com');
    const b = await logIn(t, 'ann@example.com');
    expect((await t.http.post('/v1/auth/logout').set(bearer(b))).status).toBe(204);
    expect((await t.http.get('/v1/devices').set(bearer(b))).status).toBe(401);
    expect((await t.http.get('/v1/devices').set(bearer(a))).status).toBe(200);
  });

  it('signing in again from the same machine replaces its old device instead of duplicating it', async () => {
    const first = await signUp(t, 'ann@example.com', 'windows');
    const other = await logIn(t, 'ann@example.com', 'android');
    const again = await logIn(t, 'ann@example.com', 'windows');
    const live = (await t.http.get('/v1/devices').set(bearer(again))).body.filter((d: any) => !d.revokedAt);
    expect(live.map((d: any) => d.id).sort()).toEqual([other.device.id, again.device.id].sort());
    expect((await t.http.get('/v1/devices').set(bearer(first))).status).toBe(401);
    expect((await t.http.get('/v1/devices').set(bearer(other))).status).toBe(200);
  });

  it("never exposes or lets you revoke another user's devices", async () => {
    const ann = await signUp(t, 'ann@example.com');
    const bob = await signUp(t, 'bob@example.com');
    expect((await t.http.get('/v1/devices').set(bearer(bob))).body).toHaveLength(1);
    expect((await t.http.delete(`/v1/devices/${ann.device.id}`).set(bearer(bob))).status).toBe(404);
    expect((await t.http.get('/v1/devices').set(bearer(ann))).status).toBe(200);
  });

  it('validates input at the boundary', async () => {
    const post = (b: object) => t.http.post('/v1/auth/register').send(b);
    const dev = { name: 'x', platform: 'macos' };
    expect((await post({ email: 'nope', password: 'pw-pw-pw-pw', device: dev })).status).toBe(400);
    expect((await post({ email: 'a@b.io', password: 'short', device: dev })).status).toBe(400);
    expect((await post({ email: 'a@b.io', password: 'pw-pw-pw-pw', device: { name: 'x', platform: 'palm-os' } })).status).toBe(400);
  });
});

describe('auth rate limiting', () => {
  it('returns 429 after too many attempts from one client', async () => {
    const t = await createTestApp({ throttle: true });
    try {
      let last = 0;
      for (let i = 0; i < 12; i++) {
        last = (await t.http.post('/v1/auth/login').send({ email: 'a@b.io', password: 'wrong-wrong', device: { name: 'x', platform: 'macos' } })).status;
      }
      expect(last).toBe(429);
    } finally {
      await t.close();
    }
  });
});
