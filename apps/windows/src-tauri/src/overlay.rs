//! Windows-only. One always-on-top, undecorated window per physical
//! monitor, each sized to that monitor's *work area* (excludes the
//! taskbar) — the direct port of `AppBlocker.swift`/commit 45aa533's fix on
//! macOS: cover every screen so a blocked app isn't still fully usable on
//! whichever monitor the user's actually looking at, while sizing to the
//! work area (not the full screen) so the taskbar — and the NowFocus tray
//! icon on it — stays reachable, same as that commit kept the menu bar
//! reachable on macOS.
//!
//! *** UNVERIFIED *** — `EnumDisplayMonitors`'s callback signature and
//! `MONITORINFO` field layout are written from memory, not compiled on
//! Windows.

use tauri::{AppHandle, Manager, WebviewUrl, WebviewWindowBuilder};
use windows_sys::Win32::Foundation::{BOOL, LPARAM, RECT};
use windows_sys::Win32::Graphics::Gdi::{
    EnumDisplayMonitors, GetMonitorInfoW, HDC, HMONITOR, MONITORINFO,
};

const WINDOW_LABEL_PREFIX: &str = "shield-overlay-";
/// Matched in src/main.tsx: an overlay window loads the same frontend
/// bundle as the main window, but renders only the Shield screen — no
/// sidebar, no title bar, no polling loop duplicated across N windows'
/// worth of extra IPC traffic for the same state.
const OVERLAY_ROUTE: &str = "index.html#/shield-overlay";

pub fn show_shield(app: &AppHandle) {
    hide_shield(app);
    for (i, rect) in monitor_work_areas().into_iter().enumerate() {
        let label = format!("{WINDOW_LABEL_PREFIX}{i}");
        let width = (rect.right - rect.left).max(1) as f64;
        let height = (rect.bottom - rect.top).max(1) as f64;
        let build = WebviewWindowBuilder::new(app, &label, WebviewUrl::App(OVERLAY_ROUTE.into()))
            .decorations(false)
            .always_on_top(true)
            .skip_taskbar(true)
            .resizable(false)
            .position(rect.left as f64, rect.top as f64)
            .inner_size(width, height)
            .visible(true)
            .build();
        if let Err(e) = build {
            eprintln!("failed to create shield overlay window for a monitor: {e}");
        }
    }
}

pub fn hide_shield(app: &AppHandle) {
    for (label, window) in app.webview_windows() {
        if label.starts_with(WINDOW_LABEL_PREFIX) {
            let _ = window.close();
        }
    }
}

fn monitor_work_areas() -> Vec<RECT> {
    let mut areas: Vec<RECT> = Vec::new();
    // SAFETY: `areas` outlives this call (it's a local we don't move until
    // after `EnumDisplayMonitors` returns), and `monitor_enum_proc` never
    // retains the pointer beyond that.
    unsafe {
        EnumDisplayMonitors(
            0,
            std::ptr::null(),
            Some(monitor_enum_proc),
            &mut areas as *mut Vec<RECT> as LPARAM,
        );
    }
    areas
}

unsafe extern "system" fn monitor_enum_proc(
    monitor: HMONITOR,
    _hdc: HDC,
    _clip_rect: *mut RECT,
    data: LPARAM,
) -> BOOL {
    // SAFETY: `data` is the `&mut Vec<RECT>` passed into `EnumDisplayMonitors`
    // above, valid for the duration of the enumeration call.
    let areas = unsafe { &mut *(data as *mut Vec<RECT>) };
    let mut info: MONITORINFO = unsafe { std::mem::zeroed() };
    info.cbSize = std::mem::size_of::<MONITORINFO>() as u32;
    // SAFETY: `monitor` is a handle supplied by the enumeration itself;
    // `info` is correctly sized and zeroed above.
    if unsafe { GetMonitorInfoW(monitor, &mut info) } != 0 {
        areas.push(info.rcWork);
    }
    1 // BOOL TRUE: keep enumerating
}
