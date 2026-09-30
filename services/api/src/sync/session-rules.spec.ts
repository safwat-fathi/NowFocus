import { checkSessionChange, type SessionData } from './session-rules.js';

const T0 = new Date('2026-09-29T22:00:00Z');
const at = (min: number) => new Date(T0.getTime() + min * 60_000);
const iso = (d: Date) => d.toISOString();

const session = (over: Partial<SessionData> = {}): SessionData => ({
  id: 's1', policyId: 'p1', sessionType: 'focus', source: 'user',
  startAt: iso(T0), endAt: iso(at(60)), status: 'active', enforcementMode: 'normal', ...over,
});
const code = (v: ReturnType<typeof checkSessionChange>) => (v.ok ? 'ok' : v.code);

describe('session state machine', () => {
  it('lets a session move forward: scheduled -> active -> completed', () => {
    expect(code(checkSessionChange(session({ status: 'scheduled' }), session({ status: 'active' }), T0))).toBe('ok');
    expect(code(checkSessionChange(session(), session({ status: 'completed' }), at(60)))).toBe('ok');
  });

  it('refuses to move backwards', () => {
    expect(code(checkSessionChange(session({ status: 'active' }), session({ status: 'scheduled' }), T0))).toBe('invalid_transition');
  });

  it('keeps a terminal session terminal, but tolerates an idempotent re-push', () => {
    const done = session({ status: 'cancelled' });
    expect(code(checkSessionChange(done, session({ status: 'active' }), T0))).toBe('invalid_transition');
    expect(code(checkSessionChange(done, session({ status: 'completed' }), T0))).toBe('invalid_transition');
    expect(code(checkSessionChange(done, done, T0))).toBe('ok');
  });

  it.each(['policyId', 'sessionType', 'startAt', 'source', 'enforcementMode'] as const)('treats %s as immutable after creation', (field) => {
    const changed = { policyId: 'p2', sessionType: 'bedtime_winddown', startAt: iso(at(-5)), source: 'extension', enforcementMode: 'strict' }[field];
    expect(code(checkSessionChange(session(), session({ [field]: changed } as any), T0))).toBe('immutable_field');
  });

  it('compares startAt as an instant, not as a string', () => {
    const sameInstant = session({ startAt: '2026-09-29T22:00:00.000Z' });
    expect(code(checkSessionChange(session({ startAt: '2026-09-29T22:00:00Z' }), sameInstant, T0))).toBe('ok');
  });

  describe('locked sessions', () => {
    const locked = (over: Partial<SessionData> = {}) => session({ enforcementMode: 'locked', sessionType: 'bedtime_winddown', ...over });

    it('cannot be cancelled before endAt, but nothing stops it after', () => {
      expect(code(checkSessionChange(locked(), locked({ status: 'cancelled' }), at(30)))).toBe('session_locked');
      expect(code(checkSessionChange(locked(), locked({ status: 'cancelled' }), at(60)))).toBe('ok');
    });

    it('cannot be completed, expired or errored early (the "finish it early" bypass)', () => {
      for (const status of ['completed', 'expired', 'error'] as const) {
        expect(code(checkSessionChange(locked(), locked({ status }), at(30)))).toBe('too_early');
      }
    });

    it('may complete within the 60 s clock-skew allowance before endAt', () => {
      expect(code(checkSessionChange(locked(), locked({ status: 'completed' }), new Date(at(60).getTime() - 30_000)))).toBe('ok');
      expect(code(checkSessionChange(locked(), locked({ status: 'completed' }), new Date(at(60).getTime() - 90_000)))).toBe('too_early');
    });

    it('cannot be shortened, but can be extended', () => {
      expect(code(checkSessionChange(locked(), locked({ endAt: iso(at(10)) }), T0))).toBe('end_shortened');
      expect(code(checkSessionChange(locked(), locked({ endAt: iso(at(90)) }), T0))).toBe('ok');
    });

    it('cannot be created already-cancelled or already-completed while still running', () => {
      expect(code(checkSessionChange(null, locked({ status: 'cancelled' }), at(30)))).toBe('session_locked');
      expect(code(checkSessionChange(null, locked({ status: 'completed' }), at(30)))).toBe('too_early');
      expect(code(checkSessionChange(null, locked({ status: 'completed' }), at(61)))).toBe('ok'); // history recovery
    });
  });

  it('strict sessions can be cancelled (unlock flow is client-side) but not shortened or completed early', () => {
    const strict = (over: Partial<SessionData> = {}) => session({ enforcementMode: 'strict', ...over });
    expect(code(checkSessionChange(strict(), strict({ status: 'cancelled' }), at(30)))).toBe('ok');
    expect(code(checkSessionChange(strict(), strict({ endAt: iso(at(10)) }), T0))).toBe('end_shortened');
    expect(code(checkSessionChange(strict(), strict({ status: 'completed' }), at(30)))).toBe('too_early');
  });

  it('normal sessions are unrestricted: cancel, shorten and complete early are all fine', () => {
    expect(code(checkSessionChange(session(), session({ status: 'cancelled' }), at(5)))).toBe('ok');
    expect(code(checkSessionChange(session(), session({ endAt: iso(at(10)) }), at(5)))).toBe('ok');
    expect(code(checkSessionChange(session(), session({ status: 'completed' }), at(5)))).toBe('ok');
  });
});
