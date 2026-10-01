import { randomUUID } from 'node:crypto';
import { bearer, createTestApp, logIn, signUp, type Session, type TestApp } from './helpers/app.js';

const SEC = 1000;
const MIN = 60 * SEC;
const DAY = 24 * 60 * MIN;

describe('commitment shield (server-authoritative)', () => {
  let t: TestApp;
  let a: Session;
  let b: Session;
  const add = (s: Session, items: object[]) => t.http.post('/v1/always-blocked/items').set(bearer(s)).send({ items });
  const pull = (s: Session, cursor = 0) => t.http.get('/v1/sync/pull').query({ cursor }).set(bearer(s));
  const act = (verb: 'post' | 'delete', s: Session, id: string, tail = '') => t.http[verb](`/v1/always-blocked/items/${id}${tail}`).set(bearer(s));
  const site = (v = 'example-bad.com') => ({ targetType: 'domain', targetValue: v, displayName: v });
  const addOne = async (s = a, item: object = site()) => (await add(s, [item])).body.items[0];

  beforeEach(async () => {
    t = await createTestApp();
    a = await signUp(t, 'ann@example.com', 'macos');
    b = await logIn(t, 'ann@example.com', 'android');
  });
  afterEach(() => t.close());

  it('stamps lockedAt / graceExpiresAt / lockedUntil from server time and ignores what the client sends', async () => {
    const start = t.clock.now().getTime();
    const res = await add(a, [{ ...site(), lockedAt: '2020-01-01T00:00:00Z', lockedUntil: '2020-01-02T00:00:00Z', graceExpiresAt: '2020-01-01T00:00:01Z' }]);
    expect(res.status).toBe(201);
    const item = res.body.items[0];
    expect(item).toMatchObject({ type: 'shield_item', deleted: false, originDeviceId: a.device.id });
    expect(item.data).toMatchObject({ targetType: 'domain', targetValue: 'example-bad.com', platform: 'all', status: 'grace_period' });
    expect(Date.parse(item.data.lockedAt)).toBe(start);
    expect(Date.parse(item.data.graceExpiresAt)).toBe(start + 60 * SEC);
    expect(Date.parse(item.data.lockedUntil)).toBe(start + 14 * DAY);
  });

  it('commits a batch together under one batchId and propagates it to the other device', async () => {
    const res = await add(a, [site('one.com'), site('two.com'), { targetType: 'category', targetValue: 'preset:adult_content' }]);
    const ids = res.body.items.map((i: any) => i.id);
    expect(new Set(ids).size).toBe(3);
    expect(new Set(res.body.items.map((i: any) => i.data.batchId))).toEqual(new Set([res.body.batchId]));
    const got = (await pull(b)).body.changes.filter((c: any) => c.type === 'shield_item');
    expect(got.map((c: any) => c.id).sort()).toEqual([...ids].sort());
  });

  it('derives status from time at read: grace_period -> locked -> unlocked_active, with no writes', async () => {
    const item = await addOne();
    const status = async () => (await pull(b)).body.changes[0].data.status;
    const cursor = async () => (await pull(b)).body.cursor;
    const c0 = await cursor();
    expect(await status()).toBe('grace_period');
    t.clock.advance(61 * SEC);
    expect(await status()).toBe('locked');
    t.clock.advance(14 * DAY);
    expect(await status()).toBe('unlocked_active');
    expect(await cursor()).toBe(c0);
    expect(item.data.status).toBe('grace_period');
  });

  it('adding a target that is already active is idempotent (same item, no new change)', async () => {
    const first = await addOne();
    const cursor = (await pull(a)).body.cursor;
    const again = await add(b, [site('EXAMPLE-BAD.com')]);
    expect(again.status).toBe(201);
    expect(again.body.items[0].id).toBe(first.id);
    expect((await pull(a)).body.cursor).toBe(cursor);
    const twice = await add(a, [site('dup.com'), site('dup.com')]);
    expect(twice.body.items[0].id).toBe(twice.body.items[1].id);
  });

  it('keeps application and category targets exactly as given; only domains are case-folded', async () => {
    const pkg = 'com.miHoYo.GenshinImpact'; // Android package names are case-sensitive
    const res = await add(a, [
      { targetType: 'application', targetValue: `  ${pkg} `, displayName: 'Genshin', platform: 'android' },
      { targetType: 'application', targetValue: pkg.toLowerCase(), platform: 'android' }, // a different app, not a duplicate
      { targetType: 'category', targetValue: 'preset:Adult_Content' },
      { targetType: 'domain', targetValue: ' https://www.Bad-Site.COM/path ' },
    ]);
    const values = res.body.items.map((i: any) => i.data.targetValue);
    expect(values).toEqual([pkg, pkg.toLowerCase(), 'preset:Adult_Content', 'bad-site.com']);
    expect(new Set(res.body.items.map((i: any) => i.id)).size).toBe(4);
    const again = await add(b, [{ targetType: 'application', targetValue: pkg, platform: 'android' }]);
    expect(again.body.items[0].id).toBe(res.body.items[0].id); // still idempotent for the exact same value
  });

  it('cancel-grace works inside 60 s (boundary included) and is refused after', async () => {
    const early = await addOne(a, site('mistake.com'));
    t.clock.advance(60 * SEC);
    const ok = await act('post', b, early.id, '/cancel-grace');
    expect(ok.status).toBe(200);
    expect(ok.body).toMatchObject({ id: early.id, deleted: true });
    expect((await pull(a)).body.changes.find((c: any) => c.id === early.id)).toMatchObject({ deleted: true });

    const late = await addOne(a, site('regret.com'));
    t.clock.advance(60 * SEC + 1);
    const refused = await act('post', a, late.id, '/cancel-grace');
    expect(refused.status).toBe(409);
    expect(refused.body.code).toBe('grace_expired');
    expect((await pull(a)).body.changes.find((c: any) => c.id === late.id)).toMatchObject({ deleted: false });
  });

  it('refuses DELETE with 403 until lockedUntil, then removes it', async () => {
    const item = await addOne();
    expect((await act('delete', a, item.id)).status).toBe(403); // even inside grace: use cancel-grace
    t.clock.advance(14 * DAY - 1);
    const denied = await act('delete', b, item.id);
    expect(denied.status).toBe(403);
    expect(denied.body.code).toBe('locked');
    t.clock.advance(1);
    expect((await act('delete', b, item.id)).status).toBe(204);
    expect((await pull(a)).body.changes.find((c: any) => c.id === item.id)).toMatchObject({ deleted: true });
    expect((await act('delete', b, item.id)).status).toBe(404);
  });

  it('after expiry a target can be re-committed for another 14 days, and is locked again', async () => {
    const item = await addOne();
    t.clock.advance(60 * SEC + 1);
    const early = await act('post', a, item.id, '/recommit');
    expect(early.status).toBe(409);
    expect(early.body.code).toBe('still_locked');

    t.clock.advance(14 * DAY);
    const now = t.clock.now().getTime();
    const re = await act('post', a, item.id, '/recommit');
    expect(re.status).toBe(200);
    expect(Date.parse(re.body.data.lockedUntil)).toBe(now + 14 * DAY);
    expect(re.body.data.status).toBe('locked');
    expect((await act('delete', a, item.id)).status).toBe(403);
  });

  it('never lets one account see or touch another account\'s items', async () => {
    const item = await addOne();
    const bob = await signUp(t, 'bob@example.com', 'windows');
    expect((await pull(bob)).body.changes).toEqual([]);
    t.clock.advance(15 * DAY);
    expect((await act('delete', bob, item.id)).status).toBe(404);
    expect((await act('post', bob, item.id, '/recommit')).status).toBe(404);
    expect((await act('post', bob, randomUUID(), '/cancel-grace')).status).toBe(404);
    expect((await act('delete', bob, 'not-a-uuid')).status).toBe(404);
  });

  it('cannot be edited or created through the generic sync push', async () => {
    const item = await addOne();
    const res = await t.http.post('/v1/sync/push').set(bearer(b)).send({
      changes: [{ type: 'shield_item', id: item.id, updatedAt: new Date().toISOString(), data: { ...item.data, lockedUntil: '2020-01-01T00:00:00Z' }, deleted: true }],
    });
    expect(res.body.results[0]).toMatchObject({ status: 'rejected', code: 'read_only' });
    t.clock.advance(60 * SEC + 1);
    expect((await pull(a)).body.changes.find((c: any) => c.id === item.id).data.status).toBe('locked');
  });

  it('validates input and requires auth', async () => {
    expect((await add(a, [])).status).toBe(400);
    expect((await add(a, [{ targetType: 'planet', targetValue: 'x' }])).status).toBe(400);
    expect((await add(a, [{ targetType: 'domain', targetValue: '' }])).status).toBe(400);
    expect((await add(a, [{ ...site(), platform: 'palm-os' }])).status).toBe(400);
    const bad = await add(a, [site('good.com'), { targetType: 'domain', targetValue: 'evil.com\n1.2.3.4 bank.com' }]);
    expect(bad.status).toBe(400);
    expect(bad.body.code).toBe('invalid_domain');
    expect((await pull(a)).body.changes).toHaveLength(0); // all-or-nothing: the valid item was not committed
    expect((await add(a, Array.from({ length: 51 }, (_, i) => site(`s${i}.com`)))).status).toBe(400);
    expect((await t.http.post('/v1/always-blocked/items').send({ items: [site()] })).status).toBe(401);
  });
});
