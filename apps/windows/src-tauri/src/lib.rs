mod commands;
mod dto;
mod enforcer;
mod state;

use std::sync::Mutex;

use tauri::menu::{Menu, MenuItem};
use tauri::tray::TrayIconBuilder;
use tauri::Manager;

use commands::SharedState;
use enforcer::NoopEnforcer;
use state::AppState;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| {
            let data_dir = app.path().app_data_dir().expect("app data dir should be resolvable");
            std::fs::create_dir_all(&data_dir)?;
            let db_path = data_dir.join("NowFocus.sqlite");

            // Windows-only enforcement (hosts-file editing over the
            // background service) lands in Phase 3 behind `#[cfg(windows)]`;
            // every other host — including this dev machine — uses the
            // no-op stand-in so the UI can be built and checked against the
            // design without a Windows box. See enforcer.rs.
            let app_state = AppState::open(&db_path, Box::new(NoopEnforcer::new()))
                .map_err(|e| format!("failed to open NowFocus database at {db_path:?}: {e}"))?;
            app.manage::<SharedState>(Mutex::new(app_state));

            setup_tray(app)?;
            setup_close_to_tray(app);

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

    TrayIconBuilder::new()
        .icon(app.default_window_icon().cloned().expect("bundled window icon should exist"))
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id.as_ref() {
            "open" => show_main_window(app),
            "quit" => app.exit(0),
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let tauri::tray::TrayIconEvent::Click { button: tauri::tray::MouseButton::Left, button_state: tauri::tray::MouseButtonState::Up, .. } = event {
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
/// exits the process.
fn setup_close_to_tray(app: &tauri::App) {
    if let Some(window) = app.get_webview_window("main") {
        let hideable = window.clone();
        window.on_window_event(move |event| {
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                let _ = hideable.hide();
            }
        });
    }
}
