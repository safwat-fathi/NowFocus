export type ShieldStatus = 'grace_period' | 'locked' | 'unlocked_active';

export const GRACE_MS = 60_000;
export const LOCK_MS = 14 * 24 * 3600 * 1000; // 336 h

/** Status is derived from server time on every read, so no worker has to flip it. Archived = tombstone. */
export function shieldStatus(d: { graceExpiresAt: string; lockedUntil: string }, now: Date): ShieldStatus {
  if (now.getTime() <= Date.parse(d.graceExpiresAt)) return 'grace_period';
  return now.getTime() < Date.parse(d.lockedUntil) ? 'locked' : 'unlocked_active';
}
