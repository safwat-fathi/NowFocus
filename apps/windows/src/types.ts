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

export interface AppState {
  profiles: Profile[];
  session: Session | null;
  unlock: UnlockState | null;
  shield: Shield | null;
  health: Health;
  stats: Stats;
  commitment: Commitment | null;
  bedtime: Bedtime;
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
  | "tray";
