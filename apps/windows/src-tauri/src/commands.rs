use std::sync::Mutex;

use tauri::State;

use std::sync::Arc;

use now_focus_sync::api::DeviceInfo;

use crate::dto::{AppStateDto, ScheduleDto};
use crate::state::AppState;
use crate::TrayLabels;
use crate::sync_glue::SyncController;

pub type SharedState = Mutex<AppState>;

fn snapshot(state: &State<SharedState>) -> Result<AppStateDto, String> {
    let dto = state
        .lock()
        .map_err(|_| "app state lock poisoned".to_string())?
        .snapshot()?;
    // Every command reaches this helper, so hooking the tray refresh here
    // (rather than in each command) covers session start/end/expiry
    // uniformly — see `refresh_tray` in lib.rs.
    crate::refresh_tray();
    Ok(dto)
}

/// Like [`snapshot`], for a command that changed something the other devices should hear about.
fn changed(state: &State<SharedState>) -> Result<AppStateDto, String> {
    crate::sync_glue::nudge();
    snapshot(state)
}

#[tauri::command]
pub fn get_state(state: State<SharedState>) -> Result<AppStateDto, String> {
    snapshot(&state)
}

/// The language picked in Settings ("system", "en" or "ar"). Every window follows it through the state it polls.
#[tauri::command]
pub fn set_language(state: State<SharedState>, language: String) -> Result<AppStateDto, String> {
    state
        .lock()
        .map_err(|_| "app state lock poisoned")?
        .set_language(&language)?;
    snapshot(&state)
}

/// The tray menu and tooltip words, already translated by the UI (see `TrayLabels` in lib.rs).
#[tauri::command]
pub fn set_tray_labels(open: String, quit: String, idle: String, left: String) {
    crate::set_tray_labels(TrayLabels { open, quit, idle, left });
}

#[tauri::command]
pub fn create_profile(
    state: State<SharedState>,
    name: String,
    mode: now_focus_core::PolicyMode,
) -> Result<AppStateDto, String> {
    state
        .lock()
        .map_err(|_| "app state lock poisoned")?
        .create_profile(name, mode)?;
    changed(&state)
}

#[tauri::command]
pub fn rename_profile(
    state: State<SharedState>,
    profile_id: String,
    name: String,
) -> Result<AppStateDto, String> {
    state
        .lock()
        .map_err(|_| "app state lock poisoned")?
        .rename_profile(&profile_id, name)?;
    changed(&state)
}

#[tauri::command]
pub fn add_domain(
    state: State<SharedState>,
    profile_id: String,
    domain: String,
) -> Result<AppStateDto, String> {
    state
        .lock()
        .map_err(|_| "app state lock poisoned")?
        .add_domain(&profile_id, &domain)?;
    changed(&state)
}

#[tauri::command]
pub fn remove_domain(
    state: State<SharedState>,
    profile_id: String,
    rule_id: String,
) -> Result<AppStateDto, String> {
    state
        .lock()
        .map_err(|_| "app state lock poisoned")?
        .remove_domain(&profile_id, &rule_id)?;
    changed(&state)
}

#[tauri::command]
pub fn add_application(
    state: State<SharedState>,
    profile_id: String,
    native_identifier: String,
    display_name: String,
) -> Result<AppStateDto, String> {
    state
        .lock()
        .map_err(|_| "app state lock poisoned")?
        .add_application(&profile_id, native_identifier, display_name)?;
    changed(&state)
}

#[tauri::command]
pub fn remove_application(
    state: State<SharedState>,
    profile_id: String,
    rule_id: String,
) -> Result<AppStateDto, String> {
    state
        .lock()
        .map_err(|_| "app state lock poisoned")?
        .remove_application(&profile_id, &rule_id)?;
    changed(&state)
}

#[tauri::command]
pub fn toggle_feed(
    state: State<SharedState>,
    profile_id: String,
    feed_key: String,
) -> Result<AppStateDto, String> {
    state
        .lock()
        .map_err(|_| "app state lock poisoned")?
        .toggle_feed(&profile_id, &feed_key)?;
    changed(&state)
}

#[tauri::command]
pub fn start_session(
    state: State<SharedState>,
    profile_id: String,
    duration_minutes: i64,
    mode: String,
) -> Result<AppStateDto, String> {
    state
        .lock()
        .map_err(|_| "app state lock poisoned")?
        .start_session(&profile_id, duration_minutes, &mode)?;
    changed(&state)
}

#[tauri::command]
pub fn end_session_normal(state: State<SharedState>) -> Result<AppStateDto, String> {
    state
        .lock()
        .map_err(|_| "app state lock poisoned")?
        .end_session_normal()?;
    changed(&state)
}

#[tauri::command]
pub fn begin_unlock(
    app: tauri::AppHandle,
    state: State<SharedState>,
) -> Result<AppStateDto, String> {
    state
        .lock()
        .map_err(|_| "app state lock poisoned")?
        .begin_unlock()?;
    // Reached from the shield overlay too, whose windows can't show the main
    // window from JS, and the main window may be hidden in the tray.
    crate::show_main_window(&app);
    changed(&state)
}

#[tauri::command]
pub fn cancel_unlock(state: State<SharedState>) -> Result<AppStateDto, String> {
    state
        .lock()
        .map_err(|_| "app state lock poisoned")?
        .cancel_unlock();
    changed(&state)
}

#[tauri::command]
pub fn update_unlock_text(state: State<SharedState>, typed: String) -> Result<AppStateDto, String> {
    state
        .lock()
        .map_err(|_| "app state lock poisoned")?
        .update_unlock_text(typed)?;
    changed(&state)
}

#[tauri::command]
pub fn start_unlock_wait(state: State<SharedState>) -> Result<AppStateDto, String> {
    state
        .lock()
        .map_err(|_| "app state lock poisoned")?
        .start_unlock_wait()?;
    changed(&state)
}

#[tauri::command]
pub fn confirm_unlock(state: State<SharedState>) -> Result<AppStateDto, String> {
    state
        .lock()
        .map_err(|_| "app state lock poisoned")?
        .confirm_unlock()?;
    changed(&state)
}

/// Dev-only: see `AppState::simulate_block`. Exists so the Shield screen can
/// be exercised before the Phase 4 Win32 foreground hook is wired up.
#[tauri::command]
pub fn simulate_block(
    state: State<SharedState>,
    target_kind: String,
    target_name: String,
) -> Result<AppStateDto, String> {
    state
        .lock()
        .map_err(|_| "app state lock poisoned")?
        .simulate_block(target_kind, target_name)?;
    changed(&state)
}

#[tauri::command]
pub fn dismiss_shield(state: State<SharedState>) -> Result<AppStateDto, String> {
    state
        .lock()
        .map_err(|_| "app state lock poisoned")?
        .dismiss_shield();
    changed(&state)
}

#[tauri::command]
pub fn start_commitment(
    state: State<SharedState>,
    domains: Vec<String>,
) -> Result<AppStateDto, String> {
    state
        .lock()
        .map_err(|_| "app state lock poisoned")?
        .start_commitment(domains)?;
    changed(&state)
}

/// May return `Err` with the service's refusal message when past the 60s grace.
#[tauri::command]
pub fn clear_commitment(state: State<SharedState>) -> Result<AppStateDto, String> {
    state
        .lock()
        .map_err(|_| "app state lock poisoned")?
        .clear_commitment()?;
    changed(&state)
}

#[tauri::command]
pub fn set_bedtime(
    state: State<SharedState>,
    enabled: bool,
    wind_down_minute: i64,
    sleep_minute: i64,
    wake_minute: i64,
    lock_at_sleep: bool,
    policy_id: Option<String>,
) -> Result<AppStateDto, String> {
    state
        .lock()
        .map_err(|_| "app state lock poisoned")?
        .set_bedtime(
            enabled,
            wind_down_minute,
            sleep_minute,
            wake_minute,
            lock_at_sleep,
            policy_id,
        )?;
    changed(&state)
}

#[tauri::command]
pub fn use_pass(state: State<SharedState>) -> Result<AppStateDto, String> {
    state
        .lock()
        .map_err(|_| "app state lock poisoned")?
        .use_pass()?;
    changed(&state)
}

#[tauri::command]
pub fn schedule_cheat_day(
    state: State<SharedState>,
    day_start: String,
) -> Result<AppStateDto, String> {
    state
        .lock()
        .map_err(|_| "app state lock poisoned")?
        .schedule_cheat_day(&day_start)?;
    changed(&state)
}

#[tauri::command]
pub fn cancel_cheat_day(state: State<SharedState>) -> Result<AppStateDto, String> {
    state
        .lock()
        .map_err(|_| "app state lock poisoned")?
        .cancel_cheat_day()?;
    changed(&state)
}

#[tauri::command]
pub fn save_schedule(
    state: State<SharedState>,
    schedule: ScheduleDto,
) -> Result<AppStateDto, String> {
    state
        .lock()
        .map_err(|_| "app state lock poisoned")?
        .save_schedule(schedule)?;
    changed(&state)
}

#[tauri::command]
pub fn set_limit(
    state: State<SharedState>,
    key: String,
    label: String,
    minutes: u32,
) -> Result<AppStateDto, String> {
    state
        .lock()
        .map_err(|_| "app state lock poisoned")?
        .set_limit(&key, &label, minutes)?;
    changed(&state)
}

#[tauri::command]
pub fn delete_schedule(state: State<SharedState>, id: String) -> Result<AppStateDto, String> {
    state
        .lock()
        .map_err(|_| "app state lock poisoned")?
        .delete_schedule(&id)?;
    changed(&state)
}

// ---- Account sync. These talk to the network, so they run off the UI thread.

async fn blocking<T: Send + 'static>(
    f: impl FnOnce() -> Result<T, String> + Send + 'static,
) -> Result<T, String> {
    tauri::async_runtime::spawn_blocking(f)
        .await
        .map_err(|e| e.to_string())?
}

#[tauri::command]
pub async fn sync_sign_in(
    state: State<'_, SharedState>,
    sync: State<'_, Arc<SyncController>>,
    email: String,
    password: String,
    create: bool,
) -> Result<AppStateDto, String> {
    let ctl = sync.inner().clone();
    blocking(move || ctl.sign_in(&email, &password, create)).await?;
    snapshot(&state)
}

#[tauri::command]
pub async fn sync_sign_out(
    state: State<'_, SharedState>,
    sync: State<'_, Arc<SyncController>>,
) -> Result<AppStateDto, String> {
    let ctl = sync.inner().clone();
    blocking(move || {
        ctl.sign_out();
        Ok(())
    })
    .await?;
    snapshot(&state)
}

#[tauri::command]
pub async fn sync_now(
    state: State<'_, SharedState>,
    sync: State<'_, Arc<SyncController>>,
) -> Result<AppStateDto, String> {
    sync.nudge();
    snapshot(&state)
}

#[tauri::command]
pub async fn sync_delete_account(
    state: State<'_, SharedState>,
    sync: State<'_, Arc<SyncController>>,
    password: String,
) -> Result<AppStateDto, String> {
    let ctl = sync.inner().clone();
    blocking(move || ctl.delete_account(&password)).await?;
    snapshot(&state)
}

#[tauri::command]
pub async fn sync_devices(sync: State<'_, Arc<SyncController>>) -> Result<Vec<DeviceInfo>, String> {
    let ctl = sync.inner().clone();
    blocking(move || ctl.devices()).await
}

#[tauri::command]
pub async fn sync_revoke_device(
    sync: State<'_, Arc<SyncController>>,
    id: String,
) -> Result<(), String> {
    let ctl = sync.inner().clone();
    blocking(move || ctl.revoke_device(&id)).await
}

#[tauri::command]
pub async fn sync_set_join_remote(
    state: State<'_, SharedState>,
    sync: State<'_, Arc<SyncController>>,
    on: bool,
) -> Result<AppStateDto, String> {
    sync.set_join_remote(on)?;
    snapshot(&state)
}
