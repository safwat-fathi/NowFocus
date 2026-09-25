import { invoke } from "@tauri-apps/api/core";
import type { AppState } from "../types";

// One wrapper per #[tauri::command] in src-tauri/src/commands.rs. Every
// mutating call returns the fresh AppState — same "recompute everything
// from state" shape the design's own renderVals() used, so the frontend
// never needs its own cache-invalidation logic, just setState on the reply.

export const api = {
  getState: () => invoke<AppState>("get_state"),

  createProfile: (name: string) => invoke<AppState>("create_profile", { name }),
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
};
