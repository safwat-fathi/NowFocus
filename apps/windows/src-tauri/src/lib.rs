#[cfg(windows)]
mod app_blocker;
mod commands;
mod dto;
mod enforcer;
#[cfg(windows)]
mod overlay;
#[cfg(windows)]
mod service_client;
#[cfg(windows)]
mod service_registration;
mod state;

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

            let _ = TRAY_APP.set(app.handle().clone());

            setup_tray(app)?;
            setup_close_to_tray(app);
            refresh_tray();

            // No periodic check existed on this platform before (macOS has
            // AppDelegate's 30s Timer) — without one, a session expiring
            // while the window is hidden in the tray would leave the icon
            // stuck on "active" until something else happened to poll.
            thread::spawn(|| loop {
                thread::sleep(Duration::from_secs(30));
                refresh_tray();
            });

            // User-level, no elevation needed — same as macOS's AppBlocker
            // running in the user-level NowFocus app, not the daemon.
            #[cfg(windows)]
            app_blocker::install(app.handle().clone());

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
    let open_item = MenuItem::with_id(app, "open", "Open NowFocus", true, None::<&str>)?;
    let quit_item = MenuItem::with_id(app, "quit", "Quit NowFocus", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&open_item, &quit_item])?;

    TrayIconBuilder::with_id("main")
        .icon(TRAY_LIGHT_IDLE)
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id.as_ref() {
            "open" => show_main_window(app),
            "quit" => app.exit(0),
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

    let active = {
        let Ok(mut guard) = state.lock() else { return };
        match guard.snapshot() {
            Ok(dto) => dto.session.is_some(),
            Err(_) => return,
        }
    };

    let is_dark = app
        .get_webview_window("main")
        .and_then(|w| w.theme().ok())
        .map(|t| t == Theme::Dark)
        .unwrap_or(false);

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
