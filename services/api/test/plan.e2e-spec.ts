import { randomUUID } from 'node:crypto';
import WebSocket from 'ws';
import { bearer, createTestApp, signUp, type Session, type TestApp } from './helpers/app.js';

const ADMIN = 'a'.repeat(32);
const admin = { Authorization: `Bearer ${ADMIN}` };
const HOUR = 3_600_000;

const pull = (t: TestApp, s: Session) => t.http.get('/v1/sync/pull').query({ cursor: 0 }).set(bearer(s));
const push = (t: TestApp, s: Session) => {
  const id = randomUUID();
  return t.http.post('/v1/sync/push').set(bearer(s)).send({ changes: [{ type: 'policy', id, updatedAt: new Date().toISOString(), data: { id, name: 'p' } }] });
};
const setPlan = (t: TestApp, body: object) => t.http.post('/v1/admin/plan').set(admin).send(body);

/** Resolves with the first thing the server does on a fresh socket: a message, or a close code. */
const dial = (t: TestApp, s: Session) =>
  new Promise<{ message?: { type: string; cursor: number }; closed?: number }>((resolve) => {
    const ws = new WebSocket(t.url.replace('http', 'ws') + '/ws/device', { headers: bearer(s) });
    ws.on('message', (m) => {
      resolve({ message: JSON.parse(m.toString()) });
      ws.terminate();
    });
    ws.on('close', (code) => resolve({ closed: code }));
  });

describe('Pro gate, dormant (PRO_ENFORCED off)', () => {
  let t: TestApp;
  beforeEach(async () => {
    delete process.env.PRO_ENFORCED;
    t = await createTestApp();
  });
  afterEach(() => t.close());

  it('lets a free account sync exactly as before, so deploying the gate changes nothing', async () => {
    const s = await signUp(t, 'ann@example.com');
    expect((await pull(t, s)).status).toBe(200);
    expect((await push(t, s)).status).toBe(200);
    expect((await dial(t, s)).message?.type).toBe('changes');
  });
});

describe('Pro gate, enforced (PRO_ENFORCED=1)', () => {
  let t: TestApp;
  let s: Session;
  beforeEach(async () => {
    process.env.PRO_ENFORCED = '1';
    process.env.ADMIN_TOKEN = ADMIN;
    t = await createTestApp();
    s = await signUp(t, 'ann@example.com');
  });
  afterEach(async () => {
    delete process.env.PRO_ENFORCED;
    delete process.env.ADMIN_TOKEN;
    await t.close();
  });

  it('refuses sync to a free account with 403 pro_required (never 401), and leaves everything else alone', async () => {
    for (const res of [await pull(t, s), await push(t, s)]) {
      expect(res.status).toBe(403);
      expect(res.body).toMatchObject({ statusCode: 403, code: 'pro_required' });
    }
    expect((await t.http.get('/v1/me').set(bearer(s))).status).toBe(200);
    expect((await t.http.get('/v1/devices').set(bearer(s))).status).toBe(200);
    // The 14-day shield is a free feature.
    const shield = await t.http.post('/v1/always-blocked/items').set(bearer(s)).send({ items: [{ targetType: 'domain', targetValue: 'bad.example' }] });
    expect(shield.status).toBe(201);
  });

  it('applies a grant and a revoke to the very same access token', async () => {
    expect((await pull(t, s)).status).toBe(403);

    const granted = await setPlan(t, { email: 'ANN@Example.com', plan: 'pro' });
    expect(granted.status).toBe(200);
    expect(granted.body).toEqual({ email: 'ann@example.com', plan: 'pro', until: null });
    expect((await pull(t, s)).status).toBe(200);
    expect((await push(t, s)).status).toBe(200);

    expect((await setPlan(t, { email: 'ann@example.com', plan: 'free' })).status).toBe(200);
    expect((await pull(t, s)).status).toBe(403);
  });

  it('lets a time-boxed grant lapse on its end date, and ignores an end date on a free plan', async () => {
    const until = new Date(t.clock.now().getTime() + HOUR).toISOString();
    expect((await setPlan(t, { email: 'ann@example.com', plan: 'pro', until })).status).toBe(200);
    expect((await pull(t, s)).status).toBe(200);

    t.clock.advance(2 * HOUR);
    expect((await pull(t, s)).status).toBe(403);

    const free = await setPlan(t, { email: 'ann@example.com', plan: 'free', until });
    expect(free.body.until).toBeNull();
  });

  it('closes a free account\'s socket with 4402 and welcomes a Pro one', async () => {
    expect((await dial(t, s)).closed).toBe(4402);
    await setPlan(t, { email: 'ann@example.com', plan: 'pro' });
    expect((await dial(t, s)).message).toMatchObject({ type: 'changes' });
  });

  it('guards the admin route and validates its body', async () => {
    const body = { email: 'ann@example.com', plan: 'pro' };
    expect((await t.http.post('/v1/admin/plan').send(body)).status).toBe(401);
    expect((await t.http.post('/v1/admin/plan').set('Authorization', 'Bearer nope').send(body)).status).toBe(401);
    expect((await setPlan(t, { email: 'nobody@example.com', plan: 'pro' })).status).toBe(404);
    expect((await setPlan(t, { email: 'ann@example.com', plan: 'gold' })).status).toBe(400);
    expect((await setPlan(t, { email: 'not-an-email', plan: 'pro' })).status).toBe(400);
    expect((await setPlan(t, { email: 'ann@example.com', plan: 'pro', until: 'tomorrow' })).status).toBe(400);
  });
});
