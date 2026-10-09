#[cfg(windows)]
mod app_blocker;
#[cfg(windows)]
mod browser_guard;
mod commands;
mod credentials;
mod dto;
mod enforcer;
#[cfg(windows)]
mod overlay;
#[cfg(windows)]
mod service_client;
#[cfg(windows)]
mod service_registration;
mod state;
mod sync_glue;

use std::sync::{Mutex, OnceLock};
use std::thread;
use std::time::Duration;

use tauri::image::Image;
use tauri::menu::{Menu, MenuItem};
use tauri::tray::TrayIconBuilder;
use tauri::{AppHandle, Manager, Theme};

use commands::SharedState;
use enforcer::Enforcer;
use state::AppState;

/// Set once in `setup()`, read by `refresh_tray()` from wherever it's
/// called (the tick thread, a theme-change event, or after any command that
/// might have changed session state) — avoids threading an `AppHandle`
/// through every call site that needs to touch the tray.
static TRAY_APP: OnceLock<AppHandle> = OnceLock::new();

/// Last icon actually applied, so `refresh_tray()` can skip a redundant
/// `set_icon` call when neither session state nor taskbar theme changed.
static LAST_TRAY_STATE: Mutex<Option<(bool, bool)>> = Mutex::new(None);

/// The tray's words in the app's language. The UI owns every translation and sends them, so Rust holds none:
/// `left` is a template with `{n}` for the minutes. Until the UI has sent them the tray speaks English.
#[derive(Clone)]
pub(crate) struct TrayLabels {
    pub open: String,
    pub quit: String,
    pub idle: String,
    pub left: String,
}

impl Default for TrayLabels {
    fn default() -> Self {
        Self {
            open: "Open NowFocus".into(),
            quit: "Quit NowFocus".into(),
            idle: "NowFocus".into(),
            left: "NowFocus · {n}m left".into(),
        }
    }
}

static TRAY_LABELS: Mutex<Option<TrayLabels>> = Mutex::new(None);

/// The two menu items, kept so a language change can retitle them.
static TRAY_ITEMS: OnceLock<(MenuItem<tauri::Wry>, MenuItem<tauri::Wry>)> = OnceLock::new();

fn tray_labels() -> TrayLabels {
    TRAY_LABELS.lock().unwrap().clone().unwrap_or_default()
}

/// Applies new tray words: the menu now, the tooltip on the refresh below (it only rewrites when the text changed).
pub(crate) fn set_tray_labels(labels: TrayLabels) {
    if let Some((open, quit)) = TRAY_ITEMS.get() {
        let _ = open.set_text(&labels.open);
        let _ = quit.set_text(&labels.quit);
    }
    *TRAY_LABELS.lock().unwrap() = Some(labels);
    refresh_tray();
}

/// Last tooltip applied, so it is only rewritten when the minute label changes.
static LAST_TRAY_TOOLTIP: Mutex<Option<String>> = Mutex::new(None);

// Tray icons: one 32px thick-stroke render per idle/active x light/dark —
// `include_image!` decodes at compile time, so no `image-png` Cargo feature
// is needed (see the brand-import plan for why 32px/one-size-each).
const TRAY_LIGHT_IDLE: Image<'static> = tauri::include_image!("./icons/tray/light-idle.png");
const TRAY_LIGHT_ACTIVE: Image<'static> = tauri::include_image!("./icons/tray/light-active.png");
const TRAY_DARK_IDLE: Image<'static> = tauri::include_image!("./icons/tray/dark-idle.png");
const TRAY_DARK_ACTIVE: Image<'static> = tauri::include_image!("./icons/tray/dark-active.png");

/// The real enforcer on Windows (named pipe to the background service);
/// everywhere else, including this dev machine, the no-op stand-in — see
/// enforcer.rs. `service_registration::check_and_start` is best-effort and
/// logged, not fatal: an unreachable service surfaces through
/// `Enforcer::health()` on every poll (Devices screen), which is the
/// correct place for the user to see and act on it, not a startup crash.
fn make_enforcer() -> Box<dyn Enforcer> {
    #[cfg(windows)]
    {
        match service_registration::check_and_start() {
            service_registration::RegistrationState::Running => {}
            other => eprintln!("NowFocusService not running at startup: {other:?}"),
        }
        Box::new(service_client::WindowsServiceEnforcer::new())
    }
    #[cfg(not(windows))]
    {
        Box::new(enforcer::NoopEnforcer::new())
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .setup(|app| {
            let data_dir = app
                .path()
                .app_data_dir()
                .expect("app data dir should be resolvable");
            std::fs::create_dir_all(&data_dir)?;
            let db_path = data_dir.join("NowFocus.sqlite");

            let app_state = AppState::open(&db_path, make_enforcer())
                .map_err(|e| format!("failed to open NowFocus database at {db_path:?}: {e}"))?;
            app.manage::<SharedState>(Mutex::new(app_state));
            // Optional account sync. Does nothing, and makes no network call, until the user signs in.
            app.manage(sync_glue::start(app.handle().clone()));

            let _ = TRAY_APP.set(app.handle().clone());

            setup_tray(app)?;
            setup_close_to_tray(app);
            refresh_tray();

            // No periodic check existed on this platform before (macOS has
            // AppDelegate's 30s Timer) — without one, a session expiring
            // while the window is hidden in the tray would leave the icon
            // stuck on "active" until something else happened to poll. The
            // same tick now also drives Bedtime (start the nightly locked
            // session / lock the screen at sleep time) and refreshes the
            // cached commitment status — reusing this loop rather than adding
            // a second scheduler (macOS BedtimeScheduler is also a 30s Timer).
            thread::spawn(|| loop {
                thread::sleep(Duration::from_secs(30));
                periodic_tick();
            });

            // User-level, no elevation needed — same as macOS's AppBlocker
            // running in the user-level NowFocus app, not the daemon.
            #[cfg(windows)]
            app_blocker::install(app.handle().clone());
            #[cfg(windows)]
            browser_guard::install(app.handle().clone());

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::get_state,
            commands::create_profile,
            commands::rename_profile,
            commands::add_domain,
            commands::remove_domain,
            commands::add_application,
            commands::remove_application,
            commands::toggle_feed,
            commands::start_session,
            commands::end_session_normal,
            commands::begin_unlock,
            commands::cancel_unlock,
            commands::update_unlock_text,
            commands::start_unlock_wait,
            commands::confirm_unlock,
            commands::simulate_block,
            commands::dismiss_shield,
            commands::start_commitment,
            commands::clear_commitment,
            commands::set_bedtime,
            commands::use_pass,
            commands::schedule_cheat_day,
            commands::cancel_cheat_day,
            commands::save_schedule,
            commands::delete_schedule,
            commands::set_limit,
            commands::sync_sign_in,
            commands::sync_sign_out,
            commands::sync_now,
            commands::sync_delete_account,
            commands::report_issue,
            commands::sync_devices,
            commands::sync_revoke_device,
            commands::sync_set_join_remote,
            commands::set_language,
            commands::set_tray_labels,
        ])
        .run(tauri::generate_context!())
        .expect("error while running the NowFocus app");
}

/// System tray icon: left-click toggles the window (this is the design's
/// "Tray" flyout concept, minus a true anchored popup — see the Phase 2
/// scope note in the implementation plan), right-click/menu offers Open and
/// Quit. Quit is the only path that actually exits the process; closing the
/// window itself hides to tray (see `setup_close_to_tray`).
fn setup_tray(app: &tauri::App) -> tauri::Result<()> {
    let labels = tray_labels();
    let open_item = MenuItem::with_id(app, "open", &labels.open, true, None::<&str>)?;
    let quit_item = MenuItem::with_id(app, "quit", &labels.quit, true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&open_item, &quit_item])?;
    let _ = TRAY_ITEMS.set((open_item.clone(), quit_item.clone()));

    TrayIconBuilder::with_id("main")
        .icon(TRAY_LIGHT_IDLE)
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id.as_ref() {
            "open" => show_main_window(app),
            // Quit is refused while a session is active (any mode), mirroring
            // macOS `applicationShouldTerminate` — quitting would silently drop
            // app-blocking. The user must end the session in-app first (subject
            // to its enforcement mode). OS shutdown/logoff is not routed here,
            // so it is never blocked. Bring the window forward to show why.
            "quit" => {
                if session_is_active(app) {
                    show_main_window(app);
                } else {
                    app.exit(0);
                }
            }
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let tauri::tray::TrayIconEvent::Click {
                button: tauri::tray::MouseButton::Left,
                button_state: tauri::tray::MouseButtonState::Up,
                ..
            } = event
            {
                show_main_window(tray.app_handle());
            }
        })
        .build(app)?;
    Ok(())
}

fn show_main_window(app: &tauri::AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.set_focus();
    }
}

/// Intercepts the OS-level close request (e.g. Cmd+Q, Alt+F4) and hides the
/// window instead of quitting — "NowFocus lives in the system tray when the
/// window is closed," per the design. Only the tray menu's explicit Quit
/// exits the process. Also re-picks the tray icon when the OS theme changes
/// (light/dark taskbar) — see `refresh_tray`'s doc comment for the caveat
/// about a window that's currently hidden to the tray.
fn setup_close_to_tray(app: &tauri::App) {
    if let Some(window) = app.get_webview_window("main") {
        let hideable = window.clone();
        window.on_window_event(move |event| match event {
            tauri::WindowEvent::CloseRequested { api, .. } => {
                api.prevent_close();
                let _ = hideable.hide();
            }
            tauri::WindowEvent::ThemeChanged(_) => refresh_tray(),
            _ => {}
        });
    }
}

/// Re-picks the tray icon from current session state + taskbar theme, and
/// applies it only if either actually changed since the last call. Callable
/// from anywhere once `TRAY_APP` is set (the 30s tick, a theme-change
/// event, or after any command that could have changed session state —
/// wired once, in `commands::snapshot()`, since every command already
/// funnels through it).
///
/// The taskbar-vs-app theme distinction (`Theme::Dark`/`Theme::Light` here
/// should reflect the taskbar, not the app's own theme) and whether a
/// window hidden via close-to-tray still receives `ThemeChanged` are both
/// unverified on this machine — see the brand-import plan.
pub(crate) fn refresh_tray() {
    let Some(app) = TRAY_APP.get() else { return };
    let Some(state) = app.try_state::<SharedState>() else {
        return;
    };

    let (active, tooltip) = {
        let Ok(mut guard) = state.lock() else { return };
        match guard.snapshot() {
            Ok(dto) => {
                // Minute granularity: this runs on every command, so a per-second label would rewrite the tooltip constantly.
                let labels = tray_labels();
                let tip = match &dto.session {
                    Some(s) => labels.left.replace(
                        "{n}",
                        &((s.remaining_ms.max(0) + 59_999) / 60_000).to_string(),
                    ),
                    None => labels.idle,
                };
                (dto.session.is_some(), tip)
            }
            Err(_) => return,
        }
    };

    let is_dark = app
        .get_webview_window("main")
        .and_then(|w| w.theme().ok())
        .map(|t| t == Theme::Dark)
        .unwrap_or(false);

    // Tooltip before the icon dedupe below: the icon doesn't change as the minutes tick down.
    {
        let mut last = LAST_TRAY_TOOLTIP.lock().unwrap();
        if last.as_deref() != Some(tooltip.as_str()) {
            if let Some(tray) = app.tray_by_id("main") {
                let _ = tray.set_tooltip(Some(tooltip.as_str()));
            }
            *last = Some(tooltip);
        }
    }

    {
        let mut last = LAST_TRAY_STATE.lock().unwrap();
        if *last == Some((active, is_dark)) {
            return;
        }
        *last = Some((active, is_dark));
    }

    let image = match (is_dark, active) {
        (false, false) => TRAY_LIGHT_IDLE,
        (false, true) => TRAY_LIGHT_ACTIVE,
        (true, false) => TRAY_DARK_IDLE,
        (true, true) => TRAY_DARK_ACTIVE,
    };
    if let Some(tray) = app.tray_by_id("main") {
        let _ = tray.set_icon(Some(image));
    }
}

/// The 30s background tick: advances Bedtime (may start the nightly locked
/// session or return that the screen should lock now), refreshes the cached
/// commitment status, and re-picks the tray icon. One loop, not a second
/// scheduler.
fn periodic_tick() {
    let Some(app) = TRAY_APP.get() else { return };
    if let Some(state) = app.try_state::<SharedState>() {
        let lock_now = {
            let Ok(mut guard) = state.lock() else { return };
            let lock = guard.bedtime_tick();
            guard.schedule_tick();
            // Re-applies a session after a restart, pauses it for a cheat day and brings it back when the day ends.
            guard.reconcile_enforcement();
            guard.refresh_commitment();
            lock
        };
        if lock_now {
            lock_workstation();
        }
    }
    #[cfg(windows)]
    app_blocker::recheck_foreground();
    refresh_tray();
}

/// Whether a focus/bedtime session is currently active — backs the tray Quit
/// guard (mirrors `now_focus_core::session_engine::can_quit`).
fn session_is_active(app: &AppHandle) -> bool {
    let Some(state) = app.try_state::<SharedState>() else {
        return false;
    };
    let Ok(mut guard) = state.lock() else {
        return false;
    };
    guard
        .snapshot()
        .map(|dto| dto.session.is_some())
        .unwrap_or(false)
}

/// Lock the interactive desktop at bedtime's sleep moment. Shells `rundll32`
/// rather than calling `LockWorkStation` via FFI — a well-known, dependency-
/// free path that keeps the Win32 surface thin (runs user-level, which is
/// correct for locking the current session).
#[cfg(windows)]
fn lock_workstation() {
    if let Err(e) = std::process::Command::new("rundll32.exe")
        .args(["user32.dll,LockWorkStation"])
        .status()
    {
        eprintln!("failed to lock workstation for bedtime: {e}");
    }
}

#[cfg(not(windows))]
fn lock_workstation() {
    eprintln!("[dev] bedtime sleep-time lock would fire now (no-op off Windows)");
}
