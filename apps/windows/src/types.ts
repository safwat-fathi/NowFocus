// Mirrors apps/windows/src-tauri/src/dto.rs field-for-field. Keep in sync by
// hand — there's no codegen step for this yet (add one if the DTOs start
// drifting in practice).

export interface DomainRule {
  id: string;
  domain: string;
}

export interface ApplicationRule {
  id: string;
  displayName: string;
  nativeIdentifier: string;
}

export interface FeedRule {
  feedKey: string;
  label: string;
  sub: string;
  enabled: boolean;
}

export interface Profile {
  id: string;
  name: string;
  domains: DomainRule[];
  applications: ApplicationRule[];
  feeds: FeedRule[];
}

export type SessionMode = "normal" | "strict" | "locked";

export interface Session {
  id: string;
  profileId: string;
  profileName: string;
  mode: SessionMode;
  status: string;
  startAt: string;
  endAt: string;
  remainingMs: number;
  remainingLabel: string;
  progressPct: number;
  /** A cheat day is pausing this session's blocking. */
  paused: boolean;
}

export type UnlockPhase = "typing" | "waiting";

export interface UnlockState {
  phase: UnlockPhase;
  sentence: string;
  typed: string;
  matches: boolean;
  waitRemainingMs: number;
  waitTotalMs: number;
  lockedMode: boolean;
}

export interface DeviceLayer {
  name: string;
  state: string;
  healthy: boolean;
}

export interface Health {
  websiteBlocking: string;
  appBlocking: string;
  layers: DeviceLayer[];
}

export interface TargetCount {
  name: string;
  count: number;
}

export interface Stats {
  /** 0-100 for this week, null with no sessions. */
  focusScore: number | null;
  /** Counts-only text for "Copy this week". */
  weekSummary: string;
  todayMinutes: number;
  sessionsCompleted: number;
  sessionsStarted: number;
  blockAttemptsToday: number;
  /** Minutes focused per day, Monday..Sunday (7 entries), this week. */
  weekMinutes: number[];
  streakDays: number;
  /** 0..1 completed/started this week. */
  completionRate: number;
  topTargets: TargetCount[];
}

export interface Shield {
  targetKind: string;
  targetName: string;
  /** Passes the running session still has for this app (0 = none offered). */
  passesLeft: number;
}

export interface Limit {
  key: string;
  label: string;
  isSite: boolean;
  /** Minutes in force now; 0 = none. */
  minutes: number;
  usedMinutes: number;
  usedUp: boolean;
  /** "removed at midnight" / "60 min from midnight" while a change waits. */
  pending: string | null;
}

export interface Schedule {
  /** Empty for a new schedule. */
  id: string;
  name: string;
  /** 0 = Monday .. 6 = Sunday: the days the window starts on. */
  days: number[];
  startMinute: number;
  endMinute: number;
  policyId: string;
  mode: SessionMode;
  enabled: boolean;
  daysLabel: string;
}

export interface CheatDay {
  startAt: string;
  endAt: string;
  active: boolean;
  upcoming: boolean;
}

export interface Commitment {
  domains: string[];
  endAt: string;
  canCancelNow: boolean;
  remainingSecs: number;
}

export interface Bedtime {
  enabled: boolean;
  windDownMinute: number;
  sleepMinute: number;
  wakeMinute: number;
  lockAtSleep: boolean;
  policyId: string | null;
}

export interface SyncStatus {
  signedIn: boolean;
  email: string | null;
  syncing: boolean;
  lastSyncedAt: string | null;
  /** Human-readable, e.g. "Offline. Will retry." null when all is well. */
  problem: string | null;
  /** Changes the server refused (they stay on this PC; editing them again retries). */
  rejected: number;
  /** Whether this PC joins sessions started on the account's other devices. */
  joinRemote: boolean;
}

export interface DeviceInfo {
  id: string;
  name: string;
  platform: string;
  current: boolean;
  revoked: boolean;
}

export interface AppState {
  profiles: Profile[];
  session: Session | null;
  unlock: UnlockState | null;
  shield: Shield | null;
  health: Health;
  stats: Stats;
  commitment: Commitment | null;
  bedtime: Bedtime;
  schedules: Schedule[];
  cheatDay: CheatDay | null;
  limits: Limit[];
  /** Day starts (RFC3339) a cheat day can still be planned for. */
  cheatOptions: string[];
  sync: SyncStatus;
}

export type ScreenId =
  | "onboard"
  | "focus"
  | "active"
  | "profiles"
  | "devices"
  | "stats"
  | "commitment"
  | "bedtime"
  | "schedules"
  | "cheatday"
  | "limits"
  | "tray";
