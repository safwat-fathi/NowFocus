import { invoke } from "@tauri-apps/api/core";
import { ask } from "@tauri-apps/plugin-dialog";
import type { T } from "../i18n";
import type { AppState, DeviceInfo, PolicyMode, RuleGroup, Schedule } from "../types";

// One wrapper per #[tauri::command] in src-tauri/src/commands.rs. Every
// mutating call returns the fresh AppState — same "recompute everything
// from state" shape the design's own renderVals() used, so the frontend
// never needs its own cache-invalidation logic, just setState on the reply.

export const api = {
  getState: () => invoke<AppState>("get_state"),

  createProfile: (name: string, mode: PolicyMode = "blocklist") =>
    invoke<AppState>("create_profile", { name, mode }),
  renameProfile: (profileId: string, name: string) =>
    invoke<AppState>("rename_profile", { profileId, name }),
  addDomain: (profileId: string, domain: string) =>
    invoke<AppState>("add_domain", { profileId, domain }),
  removeDomain: (profileId: string, ruleId: string) =>
    invoke<AppState>("remove_domain", { profileId, ruleId }),
  addApplication: (profileId: string, nativeIdentifier: string, displayName: string) =>
    invoke<AppState>("add_application", { profileId, nativeIdentifier, displayName }),
  removeApplication: (profileId: string, ruleId: string) =>
    invoke<AppState>("remove_application", { profileId, ruleId }),
  saveGroupFromProfile: (profileId: string) => invoke<AppState>("save_group_from_profile", { profileId }),
  updateGroup: (group: RuleGroup) => invoke<AppState>("update_group", { group }),
  deleteGroup: (groupId: string) => invoke<AppState>("delete_group", { groupId }),
  applyGroup: (profileId: string, groupId: string) => invoke<AppState>("apply_group", { profileId, groupId }),
  toggleFeed: (profileId: string, feedKey: string) =>
    invoke<AppState>("toggle_feed", { profileId, feedKey }),

  startSession: (profileId: string, durationMinutes: number, mode: string) =>
    invoke<AppState>("start_session", { profileId, durationMinutes, mode }),
  endSessionNormal: () => invoke<AppState>("end_session_normal"),

  beginUnlock: () => invoke<AppState>("begin_unlock"),
  cancelUnlock: () => invoke<AppState>("cancel_unlock"),
  updateUnlockText: (typed: string) => invoke<AppState>("update_unlock_text", { typed }),
  startUnlockWait: () => invoke<AppState>("start_unlock_wait"),
  confirmUnlock: () => invoke<AppState>("confirm_unlock"),

  simulateBlock: (targetKind: string, targetName: string) =>
    invoke<AppState>("simulate_block", { targetKind, targetName }),
  dismissShield: () => invoke<AppState>("dismiss_shield"),

  syncSignIn: (email: string, password: string) => invoke<AppState>("sync_sign_in", { email, password }),
  syncCreateAccount: (email: string, password: string) => invoke<void>("sync_create_account", { email, password }),
  syncSignOut: () => invoke<AppState>("sync_sign_out"),
  syncNow: () => invoke<AppState>("sync_now"),
  syncDeleteAccount: (password: string) => invoke<AppState>("sync_delete_account", { password }),
  reportIssue: (message: string, contact: string) => invoke<void>("report_issue", { message, contact }),
  syncDevices: () => invoke<DeviceInfo[]>("sync_devices"),
  syncRevokeDevice: (id: string) => invoke<void>("sync_revoke_device", { id }),
  syncSetJoinRemote: (on: boolean) => invoke<AppState>("sync_set_join_remote", { on }),
  setLanguage: (language: string) => invoke<AppState>("set_language", { language }),
  setDns: (provider: string, custom: string, alwaysOn: boolean) => invoke<AppState>("set_dns", { provider, custom, alwaysOn }),
  setTrayLabels: (open: string, quit: string, idle: string, left: string) =>
    invoke<void>("set_tray_labels", { open, quit, idle, left }),
  usePass: () => invoke<AppState>("use_pass"),
  scheduleCheatDay: (dayStart: string) => invoke<AppState>("schedule_cheat_day", { dayStart }),
  cancelCheatDay: () => invoke<AppState>("cancel_cheat_day"),
  saveSchedule: (schedule: Schedule) => invoke<AppState>("save_schedule", { schedule }),
  setLimit: (key: string, label: string, minutes: number) => invoke<AppState>("set_limit", { key, label, minutes }),
  deleteSchedule: (id: string) => invoke<AppState>("delete_schedule", { id }),

  startCommitment: (domains: string[]) => invoke<AppState>("start_commitment", { domains }),
  // May reject with the service's refusal message once past the 60s grace.
  clearCommitment: () => invoke<AppState>("clear_commitment"),

  setBedtime: (bedtime: {
    enabled: boolean;
    windDownMinute: number;
    sleepMinute: number;
    wakeMinute: number;
    lockAtSleep: boolean;
    policyId: string | null;
  }) => invoke<AppState>("set_bedtime", bedtime),
};

/** Normal sessions end with no typing or waiting, so ask once first. Resolves to the fresh state, or
 * null when the user chose to keep going. */
export async function endNormalAfterAsking(t: T): Promise<AppState | null> {
  const ok = await ask(t("end.body"), { title: t("end.title"), kind: "warning", okLabel: t("end.ok"), cancelLabel: t("end.keep") });
  return ok ? api.endSessionNormal() : null;
}
