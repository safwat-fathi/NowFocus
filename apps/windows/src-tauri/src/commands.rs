use std::sync::Mutex;

use tauri::State;

use crate::dto::{AppStateDto, ScheduleDto};
use crate::state::AppState;

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

#[tauri::command]
pub fn get_state(state: State<SharedState>) -> Result<AppStateDto, String> {
    snapshot(&state)
}

#[tauri::command]
pub fn create_profile(state: State<SharedState>, name: String) -> Result<AppStateDto, String> {
    state
        .lock()
        .map_err(|_| "app state lock poisoned")?
        .create_profile(name)?;
    snapshot(&state)
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
    snapshot(&state)
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
    snapshot(&state)
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
    snapshot(&state)
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
    snapshot(&state)
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
    snapshot(&state)
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
    snapshot(&state)
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
    snapshot(&state)
}

#[tauri::command]
pub fn end_session_normal(state: State<SharedState>) -> Result<AppStateDto, String> {
    state
        .lock()
        .map_err(|_| "app state lock poisoned")?
        .end_session_normal()?;
    snapshot(&state)
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
    snapshot(&state)
}

#[tauri::command]
pub fn cancel_unlock(state: State<SharedState>) -> Result<AppStateDto, String> {
    state
        .lock()
        .map_err(|_| "app state lock poisoned")?
        .cancel_unlock();
    snapshot(&state)
}

#[tauri::command]
pub fn update_unlock_text(state: State<SharedState>, typed: String) -> Result<AppStateDto, String> {
    state
        .lock()
        .map_err(|_| "app state lock poisoned")?
        .update_unlock_text(typed)?;
    snapshot(&state)
}

#[tauri::command]
pub fn start_unlock_wait(state: State<SharedState>) -> Result<AppStateDto, String> {
    state
        .lock()
        .map_err(|_| "app state lock poisoned")?
        .start_unlock_wait()?;
    snapshot(&state)
}

#[tauri::command]
pub fn confirm_unlock(state: State<SharedState>) -> Result<AppStateDto, String> {
    state
        .lock()
        .map_err(|_| "app state lock poisoned")?
        .confirm_unlock()?;
    snapshot(&state)
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
    snapshot(&state)
}

#[tauri::command]
pub fn dismiss_shield(state: State<SharedState>) -> Result<AppStateDto, String> {
    state
        .lock()
        .map_err(|_| "app state lock poisoned")?
        .dismiss_shield();
    snapshot(&state)
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
    snapshot(&state)
}

/// May return `Err` with the service's refusal message when past the 60s grace.
#[tauri::command]
pub fn clear_commitment(state: State<SharedState>) -> Result<AppStateDto, String> {
    state
        .lock()
        .map_err(|_| "app state lock poisoned")?
        .clear_commitment()?;
    snapshot(&state)
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
    snapshot(&state)
}

#[tauri::command]
pub fn use_pass(state: State<SharedState>) -> Result<AppStateDto, String> {
    state
        .lock()
        .map_err(|_| "app state lock poisoned")?
        .use_pass()?;
    snapshot(&state)
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
    snapshot(&state)
}

#[tauri::command]
pub fn cancel_cheat_day(state: State<SharedState>) -> Result<AppStateDto, String> {
    state
        .lock()
        .map_err(|_| "app state lock poisoned")?
        .cancel_cheat_day()?;
    snapshot(&state)
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
    snapshot(&state)
}

#[tauri::command]
pub fn delete_schedule(state: State<SharedState>, id: String) -> Result<AppStateDto, String> {
    state
        .lock()
        .map_err(|_| "app state lock poisoned")?
        .delete_schedule(&id)?;
    snapshot(&state)
}
