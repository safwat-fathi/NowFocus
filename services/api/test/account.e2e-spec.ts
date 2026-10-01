import WebSocket from 'ws';
import { DataSource } from 'typeorm';
import { bearer, createTestApp, logIn, signUp, type TestApp } from './helpers/app.js';

describe('account', () => {
  let t: TestApp;
  beforeEach(async () => {
    t = await createTestApp();
  });
  afterEach(() => t.close());

  it('GET /v1/me returns the signed-in account', async () => {
    const s = await signUp(t, 'Ann@Example.com');
    const res = await t.http.get('/v1/me').set(bearer(s));
    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ id: s.user.id, email: 'ann@example.com' });
    expect(typeof res.body.createdAt).toBe('string');
    expect((await t.http.get('/v1/me')).status).toBe(401);
  });

  it('delete needs the password and answers a wrong one with 403, not 401', async () => {
    const s = await signUp(t, 'ann@example.com');
    const bad = await t.http.post('/v1/me/delete').set(bearer(s)).send({ password: 'nope-nope-nope' });
    expect(bad.status).toBe(403);
    expect(bad.body.code).toBe('wrong_password');
    expect((await t.http.get('/v1/me').set(bearer(s))).status).toBe(200); // still there
    expect((await t.http.post('/v1/me/delete').set(bearer(s)).send({})).status).toBe(400);
  });

  it('delete removes the user, every device, all records and the tokens, and frees the email', async () => {
    const a = await signUp(t, 'ann@example.com', 'macos');
    const b = await logIn(t, 'ann@example.com', 'android');
    await signUp(t, 'bob@example.com', 'windows');
    const id = '11111111-1111-4111-8111-111111111111';
    await t.http.post('/v1/sync/push').set(bearer(a)).send({ changes: [{ type: 'policy', id, updatedAt: new Date().toISOString(), data: { id, name: 'p' } }] });
    await t.http.post('/v1/always-blocked/items').set(bearer(a)).send({ items: [{ targetType: 'domain', targetValue: 'x.com' }] });

    expect((await t.http.post('/v1/me/delete').set(bearer(b)).send({ password: 'pw-pw-pw-pw' })).status).toBe(204);

    expect((await t.http.get('/v1/me').set(bearer(a))).status).toBe(401);
    expect((await t.http.get('/v1/sync/pull').set(bearer(b))).status).toBe(401);
    expect((await t.http.post('/v1/auth/refresh').send({ refreshToken: a.refreshToken })).status).toBe(401);
    expect((await t.http.post('/v1/auth/login').send({ email: 'ann@example.com', password: 'pw-pw-pw-pw', device: { name: 'x', platform: 'macos' } })).status).toBe(401);

    const [counts] = await t.app.get(DataSource).query(
      'select (select count(*) from users) u, (select count(*) from devices) d, (select count(*) from sync_records) r',
    );
    expect(counts).toEqual({ u: '1', d: '1', r: '0' }); // only bob remains
    // the email can be registered again as a fresh account
    expect((await t.http.post('/v1/auth/register').send({ email: 'ann@example.com', password: 'pw-pw-pw-pw', device: { name: 'x', platform: 'macos' } })).status).toBe(201);
  });

  it('delete tells every connected device and closes its socket with 4403', async () => {
    const a = await signUp(t, 'ann@example.com', 'macos');
    const b = await logIn(t, 'ann@example.com', 'android');
    const ws = new WebSocket(t.url.replace('http', 'ws') + '/ws/device', { headers: { Authorization: `Bearer ${b.accessToken}` } });
    const messages: any[] = [];
    ws.on('message', (m) => messages.push(JSON.parse(m.toString())));
    const closed = new Promise<number>((res) => ws.on('close', (code) => res(code)));
    await new Promise<void>((res) => ws.on('open', () => res()));
    while (messages.length < 1) await new Promise((r) => setTimeout(r, 10)); // the connect-time cursor message

    expect((await t.http.post('/v1/me/delete').set(bearer(a)).send({ password: 'pw-pw-pw-pw' })).status).toBe(204);
    expect(await closed).toBe(4403);
    expect(messages.at(-1)).toEqual({ type: 'device_revoked' });
  });
});

describe('account throttling', () => {
  it('rate-limits the password check on delete', async () => {
    const t = await createTestApp({ throttle: true });
    try {
      const s = await signUp(t, 'ann@example.com');
      let last = 0;
      for (let i = 0; i < 12; i++) last = (await t.http.post('/v1/me/delete').set(bearer(s)).send({ password: 'wrong-wrong-wrong' })).status;
      expect(last).toBe(429);
    } finally {
      await t.close();
    }
  });
});
