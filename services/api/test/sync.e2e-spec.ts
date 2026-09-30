import { randomUUID } from 'node:crypto';
import { bearer, createTestApp, logIn, signUp, type Session, type TestApp } from './helpers/app.js';

const MIN = 60_000;
const HOUR = 60 * MIN;

describe('sync push/pull', () => {
  let t: TestApp;
  let a: Session; // macOS
  let b: Session; // android, same account
  const iso = (ms = 0) => new Date(t.clock.now().getTime() + ms).toISOString();

  const push = (s: Session, changes: object[]) => t.http.post('/v1/sync/push').set(bearer(s)).send({ changes });
  const pull = (s: Session, cursor = 0, limit?: number) =>
    t.http.get('/v1/sync/pull').query({ cursor, ...(limit ? { limit } : {}) }).set(bearer(s));

  const policy = (id: string = randomUUID(), over: object = {}) => ({
    id, name: 'Deep work', mode: 'blocklist',
    domainRules: [{ id: randomUUID(), domain: 'youtube.com', includeSubdomains: true, enabled: true }],
    applicationRules: [{ id: randomUUID(), platform: 'macos', nativeIdentifier: 'com.apple.Music', displayName: 'Music', enabled: true }],
    ...over,
  });
  const change = (type: string, id: string, data: object, over: object = {}) => ({ type, id, updatedAt: iso(), data, ...over });
  const session = (over: object = {}) => ({
    id: randomUUID(), policyId: randomUUID(), sessionType: 'focus', source: 'user',
    startAt: iso(), endAt: iso(HOUR), status: 'active', enforcementMode: 'normal', notificationMode: 'normal', ...over,
  });

  beforeEach(async () => {
    t = await createTestApp();
    a = await signUp(t, 'ann@example.com', 'macos');
    b = await logIn(t, 'ann@example.com', 'android');
  });
  afterEach(() => t.close());

  it('a policy pushed from one device shows up for the other', async () => {
    const p = policy();
    const res = await push(a, [change('policy', p.id, p)]);
    expect(res.status).toBe(200);
    expect(res.body.results[0]).toMatchObject({ type: 'policy', id: p.id, status: 'applied' });
    expect(res.body.cursor).toBe(1);
    expect(typeof res.body.serverTime).toBe('string');

    const got = await pull(b);
    expect(got.body.changes).toHaveLength(1);
    expect(got.body.changes[0]).toMatchObject({ type: 'policy', id: p.id, revision: 1, seq: 1, deleted: false, originDeviceId: a.device.id, data: p });
    expect(got.body).toMatchObject({ cursor: 1, hasMore: false });
  });

  it('pull is incremental and pages with hasMore', async () => {
    const ps = [policy(), policy(), policy()];
    await push(a, ps.map((p) => change('policy', p.id, p)));
    const page1 = await pull(b, 0, 2);
    expect(page1.body.changes.map((c: any) => c.seq)).toEqual([1, 2]);
    expect(page1.body).toMatchObject({ hasMore: true, cursor: 2 });
    const page2 = await pull(b, page1.body.cursor, 2);
    expect(page2.body.changes.map((c: any) => c.seq)).toEqual([3]);
    expect(page2.body).toMatchObject({ hasMore: false, cursor: 3 });
    const none = await pull(b, 3);
    expect(none.body).toMatchObject({ changes: [], cursor: 3, hasMore: false });
  });

  it('last-write-wins by updatedAt: newer applies, older is stale and returns the winner', async () => {
    const id = randomUUID();
    await push(a, [change('policy', id, policy(id, { name: 'v1' }), { updatedAt: iso(-10 * MIN) })]);
    const newer = await push(b, [change('policy', id, policy(id, { name: 'v2' }), { updatedAt: iso(-5 * MIN) })]);
    expect(newer.body.results[0]).toMatchObject({ status: 'applied', record: { revision: 2 } });
    const older = await push(a, [change('policy', id, policy(id, { name: 'stale edit' }), { updatedAt: iso(-7 * MIN) })]);
    expect(older.body.results[0]).toMatchObject({ status: 'stale', record: { revision: 2, data: { name: 'v2' } } });
    expect((await pull(a)).body.changes[0].data.name).toBe('v2');
  });

  it('retrying an already-applied push is a no-op and does not advance the cursor', async () => {
    const p = policy();
    const c = change('policy', p.id, p);
    await push(a, [c]);
    const retry = await push(a, [c]);
    expect(retry.body.results[0].status).toBe('stale');
    expect(retry.body.cursor).toBe(1);
    expect((await pull(b)).body.changes).toHaveLength(1);
  });

  it('a far-future updatedAt is clamped so one bad clock cannot win forever', async () => {
    const p = policy();
    const res = await push(a, [change('policy', p.id, p, { updatedAt: iso(365 * 24 * HOUR) })]);
    expect(Date.parse(res.body.results[0].record.updatedAt)).toBeLessThanOrEqual(t.clock.now().getTime() + 5 * MIN + 1000);
  });

  it('deleting a policy leaves a tombstone without its content; a newer write can resurrect it', async () => {
    const p = policy();
    await push(a, [change('policy', p.id, p, { updatedAt: iso(-3 * MIN) })]);
    const del = await push(a, [change('policy', p.id, {}, { deleted: true, updatedAt: iso(-2 * MIN) })]);
    expect(del.body.results[0].status).toBe('applied');
    const tomb = (await pull(b)).body.changes[0];
    expect(tomb).toMatchObject({ deleted: true, revision: 2, data: {} });
    const back = await push(b, [change('policy', p.id, p, { updatedAt: iso(-1 * MIN) })]);
    expect(back.body.results[0]).toMatchObject({ status: 'applied', record: { revision: 3, deleted: false } });
  });

  it('sessions and settings singletons cannot be deleted', async () => {
    const s = session();
    await push(a, [change('session', s.id, s)]);
    const r = await push(a, [change('session', s.id, {}, { deleted: true }), change('bedtime_settings', 'default', {}, { deleted: true })]);
    expect(r.body.results.map((x: any) => x.code)).toEqual(['not_deletable', 'not_deletable']);
  });

  it('round-trips fields the server does not know (an older server must not eat newer client data)', async () => {
    const p = policy(undefined, { feedRules: ['SHORTS', 'FUTURE_RULE_X'], partial: ['YT_SHORTS'], futureField: { nested: [1, 2, { a: null }] } });
    await push(a, [change('policy', p.id, p)]);
    expect((await pull(b)).body.changes[0].data).toEqual(p);
  });

  it('normalizes UUID case so macOS-style uppercase ids match everywhere', async () => {
    const pid = randomUUID();
    const s = session({ id: randomUUID().toUpperCase(), policyId: pid.toUpperCase() });
    await push(a, [change('session', s.id, s)]);
    const got = (await pull(b)).body.changes[0];
    expect(got.id).toBe(s.id.toLowerCase());
    expect(got.data).toMatchObject({ id: s.id.toLowerCase(), policyId: pid });
  });

  it("defaults a session's missing source to 'user' (Android and Windows sessions have no such field)", async () => {
    const { source: _omit, ...s } = session() as any;
    expect((await push(a, [change('session', s.id, s)])).body.results[0].status).toBe('applied');
    expect((await pull(b)).body.changes[0].data.source).toBe('user');
    // and re-pushing the same session from a client that still omits it is not an immutable-field violation
    expect((await push(a, [change('session', s.id, { ...s, status: 'completed', endAt: s.endAt })])).body.results[0].status).toBe('applied');
  });

  it('rejects bad changes individually without failing the batch', async () => {
    const good = policy();
    const res = await push(a, [
      change('policy', good.id, good),
      change('policy', 'not-a-uuid', policy()),
      change('policy', randomUUID(), { mode: 'blocklist' }), // missing name
      change('bedtime_settings', 'default', { windDownMinute: 5000 }),
      change('bedtime_settings', 'other', { enabled: true }),
      change('session', randomUUID(), session({ status: 'sleeping' })),
      change('shield_item', randomUUID(), { targetValue: 'x.com' }),
      change('mystery', 'x', {}),
      { type: 'policy', id: randomUUID(), updatedAt: 'yesterday-ish', data: policy() },
    ]);
    expect(res.status).toBe(200);
    expect(res.body.results.map((r: any) => r.code ?? r.status)).toEqual([
      'applied', 'invalid_id', 'invalid_data', 'invalid_data', 'invalid_id', 'invalid_data', 'read_only', 'unknown_type', 'invalid_change',
    ]);
    expect((await pull(b)).body.changes).toHaveLength(1);
  });

  it('caps a batch at 100 changes and a body at 1 MB', async () => {
    expect((await push(a, Array.from({ length: 101 }, () => change('user_settings', 'default', {})))).status).toBe(400);
    const big = await push(a, [change('user_settings', 'default', { blob: 'x'.repeat(1_200_000) })]);
    expect(big.status).toBe(413);
  });

  it('syncs bedtime settings and user settings as single records', async () => {
    const bed = { enabled: true, windDownMinute: 1290, sleepMinute: 1380, wakeMinute: 420, lockAtSleep: true };
    await push(a, [change('bedtime_settings', 'default', bed), change('user_settings', 'default', { theme: 'dark' })]);
    const got = (await pull(b)).body.changes;
    expect(got.map((c: any) => [c.type, c.id])).toEqual([['bedtime_settings', 'default'], ['user_settings', 'default']]);
    expect(got[0].data).toEqual(bed);
  });

  describe('running a locked session across devices', () => {
    it('cannot be cancelled or finished early from any device, then completes at endAt', async () => {
      const s = session({ sessionType: 'bedtime_winddown', enforcementMode: 'locked', endAt: iso(8 * HOUR) });
      expect((await push(a, [change('session', s.id, s)])).body.results[0].status).toBe('applied');
      expect((await pull(b)).body.changes[0].data).toMatchObject({ id: s.id, enforcementMode: 'locked', status: 'active' });

      const cancel = await push(b, [change('session', s.id, { ...s, status: 'cancelled' })]);
      expect(cancel.body.results[0]).toMatchObject({ status: 'rejected', code: 'session_locked' });
      const early = await push(a, [change('session', s.id, { ...s, status: 'completed' })]);
      expect(early.body.results[0]).toMatchObject({ status: 'rejected', code: 'too_early' });
      const shorten = await push(a, [change('session', s.id, { ...s, endAt: iso(HOUR) })]);
      expect(shorten.body.results[0]).toMatchObject({ status: 'rejected', code: 'end_shortened' });
      const downgrade = await push(a, [change('session', s.id, { ...s, enforcementMode: 'normal' })]);
      expect(downgrade.body.results[0]).toMatchObject({ status: 'rejected', code: 'immutable_field' });

      t.clock.advance(8 * HOUR);
      const done = await push(a, [change('session', s.id, { ...s, status: 'completed', completedAt: iso() })]);
      expect(done.body.results[0].status).toBe('applied');
      expect((await pull(b, 1)).body.changes[0].data.status).toBe('completed');
    });

    it('a normal session can be cancelled from the other device', async () => {
      const s = session();
      await push(a, [change('session', s.id, s)]);
      expect((await push(b, [change('session', s.id, { ...s, status: 'cancelled' })])).body.results[0].status).toBe('applied');
    });
  });

  describe('editing a policy while a session is running on it (doc §4.5)', () => {
    const withDomains = (id: string, domains: string[], over: object = {}) =>
      policy(id, { domainRules: domains.map((d) => ({ id: randomUUID(), domain: d, includeSubdomains: true, enabled: true })), ...over });
    let pid: string;
    let sess: ReturnType<typeof session>;
    const startSession = async (over: object = {}) => {
      sess = session({ policyId: pid, ...over });
      await push(a, [change('session', sess.id, sess)]);
    };
    const edit = (s: Session, data: object, over: object = {}) => push(s, [change('policy', pid, data, { updatedAt: iso(MIN), ...over })]);
    const code = (r: any) => r.body.results[0].code ?? r.body.results[0].status;

    beforeEach(async () => {
      pid = randomUUID();
      await push(a, [change('policy', pid, withDomains(pid, ['youtube.com', 'x.com']), { updatedAt: iso(-10 * MIN) })]);
    });

    it('rejects removals and deletion from any device, but accepts additions and renames', async () => {
      await startSession({ enforcementMode: 'locked', sessionType: 'bedtime_winddown', endAt: iso(8 * HOUR) });
      expect(code(await edit(b, withDomains(pid, ['youtube.com'])))).toBe('policy_in_use');
      expect(code(await edit(b, {}, { deleted: true }))).toBe('policy_in_use');
      expect(code(await edit(b, withDomains(pid, ['youtube.com', 'x.com', 'reddit.com'], { name: 'Renamed' })))).toBe('applied');
      expect((await pull(a)).body.changes.find((c: any) => c.type === 'policy').data.domainRules).toHaveLength(3);
    });

    it('protects normal-mode sessions too, since the doc rule has no mode branching', async () => {
      await startSession();
      expect(code(await edit(b, withDomains(pid, ['youtube.com'])))).toBe('policy_in_use');
    });

    it('lets the same edit through once the session has ended', async () => {
      await startSession({ endAt: iso(HOUR) });
      expect(code(await edit(b, withDomains(pid, ['youtube.com'])))).toBe('policy_in_use');
      t.clock.advance(HOUR);
      expect(code(await edit(b, withDomains(pid, ['youtube.com']), { updatedAt: iso(MIN) }))).toBe('applied');
    });

    it('does not lock a policy for sessions that are over or have not started', async () => {
      await startSession({ status: 'cancelled' });
      await startSession({ startAt: iso(2 * HOUR), endAt: iso(3 * HOUR), status: 'scheduled' });
      expect(code(await edit(b, withDomains(pid, ['youtube.com'])))).toBe('applied');
    });

    it("only guards the policy the session runs on", async () => {
      const other = randomUUID();
      await push(a, [change('policy', other, withDomains(other, ['a.com', 'b.com']), { updatedAt: iso(-10 * MIN) })]);
      await startSession();
      expect(code(await push(b, [change('policy', other, withDomains(other, ['a.com']), { updatedAt: iso(MIN) })]))).toBe('applied');
    });
  });

  it('keeps every account isolated, even for identical ids', async () => {
    const bob = await signUp(t, 'bob@example.com', 'windows');
    const p = policy();
    await push(a, [change('policy', p.id, p)]);
    expect((await pull(bob)).body.changes).toEqual([]);
    const r = await push(bob, [change('policy', p.id, policy(p.id, { name: 'bob' }))]);
    expect(r.body.results[0]).toMatchObject({ status: 'applied', record: { revision: 1, seq: 1 } });
    expect((await pull(a)).body.changes[0].data.name).toBe('Deep work');
  });

  it('assigns gapless, unique seq numbers under concurrent pushes from two devices', async () => {
    const results = await Promise.all(
      Array.from({ length: 10 }, (_, i) => {
        const p = policy(undefined, { name: `p${i}` });
        return push(i % 2 ? a : b, [change('policy', p.id, p)]);
      }),
    );
    expect(results.map((r) => r.body.results[0].code ?? r.body.results[0].status)).toEqual(Array(10).fill('applied'));
    const seqs = (await pull(a)).body.changes.map((c: any) => c.seq);
    expect(seqs).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);
  });

  it('requires authentication', async () => {
    expect((await t.http.get('/v1/sync/pull')).status).toBe(401);
    expect((await t.http.post('/v1/sync/push').send({ changes: [] })).status).toBe(401);
  });
});
