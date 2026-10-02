export const SESSION_STATUSES = ['scheduled', 'active', 'completed', 'cancelled', 'expired', 'error'] as const;
export const SESSION_TYPES = ['focus', 'bedtime_winddown'] as const;
export const SESSION_MODES = ['normal', 'strict', 'locked'] as const;
export const SESSION_SOURCES = ['user', 'extension', 'schedule'] as const;

export interface SessionData {
  id: string;
  policyId: string;
  sessionType: (typeof SESSION_TYPES)[number];
  source: (typeof SESSION_SOURCES)[number];
  startAt: string; // ISO 8601 UTC
  endAt: string;
  status: (typeof SESSION_STATUSES)[number];
  enforcementMode: (typeof SESSION_MODES)[number];
  [extra: string]: unknown; // the rest of the payload is stored verbatim
}

export type Verdict = { ok: true } | { ok: false; code: 'invalid_transition' | 'immutable_field' | 'end_shortened' | 'session_locked' | 'too_early' | 'too_long'; message: string };

const OK: Verdict = { ok: true };
const no = (code: Extract<Verdict, { ok: false }>['code'], message: string): Verdict => ({ ok: false, code, message });

const RANK = { scheduled: 0, active: 1, completed: 2, cancelled: 2, expired: 2, error: 2 } as const;
const TERMINAL = 2;
const EARLY_ALLOWANCE_MS = 60_000; // clock skew between a device and the server
/**
 * No session may run longer than this, however it is created or extended. A locked session cannot be cancelled
 * or shortened, so without a cap one push (a buggy client, a stolen account) could lock every linked device
 * for years and nothing could undo it. A day covers a long deep-work stretch and a full night's Bedtime.
 */
export const MAX_SESSION_MS = 24 * 3_600_000;
const IMMUTABLE = ['policyId', 'sessionType', 'startAt', 'source', 'enforcementMode'] as const;

/**
 * The server-side guard against bypassing a running session by pushing a doctored state.
 * `prev` is the stored session (null on create); `now` is server time. Pure — no I/O.
 */
export function checkSessionChange(prev: SessionData | null, next: SessionData, now: Date): Verdict {
  if (Date.parse(next.endAt) - Date.parse(next.startAt) > MAX_SESSION_MS) {
    return no('too_long', 'a session can last at most 24 hours');
  }
  if (prev) {
    for (const f of IMMUTABLE) {
      const same = f === 'startAt' ? Date.parse(prev.startAt) === Date.parse(next.startAt) : prev[f] === next[f];
      if (!same) return no('immutable_field', `${f} cannot change after a session is created`);
    }
    if (RANK[prev.status] === TERMINAL ? next.status !== prev.status : RANK[next.status] < RANK[prev.status]) {
      return no('invalid_transition', `session cannot go from ${prev.status} to ${next.status}`);
    }
    if (next.enforcementMode !== 'normal' && RANK[prev.status] < TERMINAL && Date.parse(next.endAt) < Date.parse(prev.endAt)) {
      return no('end_shortened', `a ${next.enforcementMode} session can only be extended, not shortened`);
    }
  }

  const endAt = Date.parse(next.endAt);
  if (next.status === 'cancelled' && next.enforcementMode === 'locked' && now.getTime() < endAt) {
    return no('session_locked', 'a locked session cannot be cancelled before it ends');
  }
  if (RANK[next.status] === TERMINAL && next.status !== 'cancelled' && next.enforcementMode !== 'normal' && now.getTime() < endAt - EARLY_ALLOWANCE_MS) {
    return no('too_early', `a ${next.enforcementMode} session cannot be marked ${next.status} before it ends`);
  }
  return OK;
}
