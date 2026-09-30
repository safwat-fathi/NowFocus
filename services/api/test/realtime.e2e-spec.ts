import { randomUUID } from 'node:crypto';
import WebSocket from 'ws';
import { bearer, createTestApp, logIn, signUp, type Session, type TestApp } from './helpers/app.js';

const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** A connected device socket that records every message and its close code. */
function connect(t: TestApp, token?: string) {
  const ws = new WebSocket(t.url.replace('http', 'ws') + '/ws/device', { headers: token ? { Authorization: `Bearer ${token}` } : {} });
  const messages: any[] = [];
  ws.on('message', (m) => messages.push(JSON.parse(m.toString())));
  const closed = new Promise<number>((res) => ws.on('close', (code) => res(code)));
  const opened = new Promise<void>((res) => ws.on('open', () => res()));
  const next = async (n = 1, timeout = 2000) => {
    const start = Date.now();
    while (messages.length < n && Date.now() - start < timeout) await wait(10);
    return messages;
  };
  return { ws, messages, closed, opened, next };
}

describe('realtime nudges (/ws/device)', () => {
  let t: TestApp;
  let a: Session;
  let b: Session;
  const open: Array<ReturnType<typeof connect>> = [];
  // Every authenticated socket first receives the user's current cursor; wait for it instead of sleeping.
  const dial = async (token: string, { keepHello = false } = {}) => {
    const c = connect(t, token);
    open.push(c);
    expect(await c.next(1)).toHaveLength(1);
    if (!keepHello) c.messages.length = 0;
    return c;
  };
  const push = (s: Session) => {
    const id = randomUUID();
    return t.http.post('/v1/sync/push').set(bearer(s)).send({ changes: [{ type: 'policy', id, updatedAt: new Date().toISOString(), data: { id, name: 'p' } }] });
  };

  beforeEach(async () => {
    t = await createTestApp();
    a = await signUp(t, 'ann@example.com', 'macos');
    b = await logIn(t, 'ann@example.com', 'android');
  });
  afterEach(async () => {
    open.splice(0).forEach((c) => c.ws.terminate());
    await t.close();
  });

  it("tells the user's other devices that changes are available, but not the device that made them", async () => {
    const sockB = await dial(b.accessToken);
    const sockA = await dial(a.accessToken);
    await push(a);
    expect(await sockB.next()).toEqual([{ type: 'changes', cursor: 1 }]);
    await wait(200);
    expect(sockA.messages).toEqual([]);
  });

  it("tells a connecting device the current cursor, so a change made just before it registered isn't missed", async () => {
    await push(a);
    const sockB = await dial(b.accessToken, { keepHello: true });
    expect(sockB.messages).toEqual([{ type: 'changes', cursor: 1 }]);
    const fresh = await signUp(t, 'new@example.com', 'windows');
    const sockNew = await dial(fresh.accessToken, { keepHello: true });
    expect(sockNew.messages).toEqual([{ type: 'changes', cursor: 0 }]);
  });

  it('nudges on shield commits too', async () => {
    const sockB = await dial(b.accessToken);
    await t.http.post('/v1/always-blocked/items').set(bearer(a)).send({ items: [{ targetType: 'domain', targetValue: 'x.com' }] });
    expect(await sockB.next()).toEqual([{ type: 'changes', cursor: 1 }]);
  });

  it('stays quiet when a push changes nothing', async () => {
    const sockB = await dial(b.accessToken);
    await t.http.post('/v1/sync/push').set(bearer(a)).send({ changes: [{ type: 'mystery', id: 'x', updatedAt: new Date().toISOString(), data: {} }] });
    await wait(200);
    expect(sockB.messages).toEqual([]);
  });

  it("never leaks one account's activity to another account's sockets", async () => {
    const bob = await signUp(t, 'bob@example.com', 'windows');
    const sockBob = await dial(bob.accessToken);
    await push(a);
    await wait(200);
    expect(sockBob.messages).toEqual([]);
  });

  it('closes unauthenticated connections with 4401', async () => {
    const none = connect(t);
    open.push(none);
    expect(await none.closed).toBe(4401);
    const junk = connect(t, 'junk');
    open.push(junk);
    expect(await junk.closed).toBe(4401);
  });

  it('cuts off a revoked device immediately and tells it why', async () => {
    const sockB = await dial(b.accessToken);
    expect((await t.http.delete(`/v1/devices/${b.device.id}`).set(bearer(a))).status).toBe(204);
    expect(await sockB.closed).toBe(4403);
    expect(sockB.messages).toEqual([{ type: 'device_revoked' }]);
    const again = connect(t, b.accessToken);
    open.push(again);
    expect(await again.closed).toBe(4401);
  });

  it('logout also disconnects that device', async () => {
    const sockB = await dial(b.accessToken);
    await t.http.post('/v1/auth/logout').set(bearer(b));
    expect(await sockB.closed).toBe(4403);
  });
});
