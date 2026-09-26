use std::sync::Mutex;

use tauri::State;

use crate::dto::AppStateDto;
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
pub fn begin_unlock(state: State<SharedState>) -> Result<AppStateDto, String> {
    state
        .lock()
        .map_err(|_| "app state lock poisoned")?
        .begin_unlock()?;
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
