//! Windows-only soft app-blocking. Port of
//! apps/macos/NowFocus/Enforcement/AppBlocker.swift's approach — user-level,
//! no elevation needed (same reason as macOS: neither `NSWorkspace`
//! notifications nor `SetWinEventHook` need special permission) — but the
//! health check is real here rather than hardcoded, and the reason is
//! worth keeping next to the code it differs from: `SetWinEventHook` *can*
//! return `NULL` on failure, unlike `NSWorkspace` registration, which
//! macOS's own comment says "can't fail to register."
//!
//! *** UNVERIFIED — no Windows machine in this build environment ***. The
//! `WINEVENTPROC` signature, the `OBJID_WINDOW` filter, and
//! `QueryFullProcessImageNameW`'s exact parameter order are all written
//! from memory. If this silently never fires on real Windows, check those
//! three first.

use std::sync::atomic::{AtomicIsize, Ordering};
use std::sync::OnceLock;

use tauri::{AppHandle, Manager};
use windows_sys::Win32::Foundation::{CloseHandle, HWND};
use windows_sys::Win32::System::Threading::{
    OpenProcess, QueryFullProcessImageNameW, PROCESS_QUERY_LIMITED_INFORMATION,
};
use windows_sys::Win32::UI::Accessibility::{SetWinEventHook, HWINEVENTHOOK};
use windows_sys::Win32::UI::WindowsAndMessaging::{
    GetWindowThreadProcessId, PostMessageW, EVENT_SYSTEM_FOREGROUND, WINEVENT_OUTOFCONTEXT,
    WM_CLOSE,
};

use crate::commands::SharedState;

static APP_HANDLE: OnceLock<AppHandle> = OnceLock::new();
/// Raw `HWINEVENTHOOK` value (an opaque handle, i.e. just an integer) — 0
/// means "not installed" or "installation failed", matching `NULL`.
static HOOK: AtomicIsize = AtomicIsize::new(0);

/// Call once at startup. Safe to call even if it fails; `is_active()`
/// reflects the real result for the Devices screen instead of assuming success.
pub fn install(app: AppHandle) {
    let _ = APP_HANDLE.set(app);
    // SAFETY: `win_event_proc` matches the required `WINEVENTPROC` signature
    // exactly (extern "system", same parameter types) and never panics
    // across the FFI boundary (all fallible steps inside it return early
    // instead of unwrapping). `SetWinEventHook` is documented safe to call
    // with a static function pointer and no additional context; we recover
    // the app state we need via the `APP_HANDLE`/`HOOK` statics instead of a
    // context parameter, since `WINEVENTPROC` doesn't have one.
    let hook = unsafe {
        SetWinEventHook(
            EVENT_SYSTEM_FOREGROUND,
            EVENT_SYSTEM_FOREGROUND,
            0,
            Some(win_event_proc),
            0,
            0,
            WINEVENT_OUTOFCONTEXT,
        )
    };
    HOOK.store(hook, Ordering::SeqCst);
    if hook == 0 {
        eprintln!("SetWinEventHook failed — app blocking will not detect foreground changes");
    }
}

/// Whether the foreground-window hook is actually installed. Feeds the
/// Devices screen's app-blocking health row — unlike macOS's
/// `AppBlocker.checkHealth()`, this is a real check, not a hardcoded `true`,
/// because this specific API can genuinely fail.
pub fn is_active() -> bool {
    HOOK.load(Ordering::SeqCst) != 0
}

const OBJID_WINDOW: i32 = 0;

unsafe extern "system" fn win_event_proc(
    _hook: HWINEVENTHOOK,
    event: u32,
    hwnd: HWND,
    id_object: i32,
    _id_child: i32,
    _event_thread: u32,
    _event_time: u32,
) {
    if event != EVENT_SYSTEM_FOREGROUND || hwnd == 0 || id_object != OBJID_WINDOW {
        return;
    }
    let Some(exe_path) = foreground_process_path(hwnd) else {
        return;
    };
    let Some(app) = APP_HANDLE.get() else { return };

    let blocked_name = {
        let state = app.state::<SharedState>();
        let Ok(mut guard) = state.lock() else { return };
        guard.check_foreground_app(&exe_path)
    };

    if blocked_name.is_some() {
        // Graceful close, not TerminateProcess — respects unsaved-work
        // prompts, mirrors macOS's `app.terminate()` (not `forceTerminate()`).
        // SAFETY: `hwnd` came from this same callback's parameter, valid for
        // the duration of this call.
        unsafe {
            PostMessageW(hwnd, WM_CLOSE, 0, 0);
        }
        crate::overlay::show_shield(app);
    }
}

/// Resolves the executable image path for the process that owns `hwnd`.
/// Returns `None` on any failure (process already exited, access denied,
/// etc.) — those are normal races, not something to log on every
/// foreground change.
fn foreground_process_path(hwnd: HWND) -> Option<String> {
    let mut pid: u32 = 0;
    // SAFETY: `hwnd` is a valid window handle from the hook callback;
    // `pid` is a valid out-param.
    unsafe { GetWindowThreadProcessId(hwnd, &mut pid) };
    if pid == 0 {
        return None;
    }

    // SAFETY: PROCESS_QUERY_LIMITED_INFORMATION is the minimum access this
    // needs and works even for processes owned by other users/elevated
    // processes, which a plain user-level `AppBlocker` should expect to see.
    let handle = unsafe { OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid) };
    if handle == 0 {
        return None;
    }

    let mut buf = [0u16; 1024];
    let mut size = buf.len() as u32;
    // SAFETY: `handle` was just opened above; `buf`/`size` are a valid
    // writable buffer and its capacity.
    let ok = unsafe { QueryFullProcessImageNameW(handle, 0, buf.as_mut_ptr(), &mut size) };
    // SAFETY: `handle` was opened above and isn't used again after this.
    unsafe { CloseHandle(handle) };

    if ok == 0 {
        return None;
    }
    Some(String::from_utf16_lossy(&buf[..size as usize]))
}
