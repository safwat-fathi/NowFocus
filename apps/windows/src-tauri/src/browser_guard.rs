//! Windows-only: closes the front browser's tab when it is on a page one of the session's feed rules names
//! (YouTube Shorts, Instagram Reels / Explore, Facebook Reels). Desktop cannot see inside a page, so this
//! reads the address bar through UI Automation (user level, no elevation, the same reason `app_blocker`
//! needs none) and presses Ctrl+W. The address is matched in memory by `now_focus_core::feed_url` and never
//! stored, logged or sent; only the rule's label goes into the block event.
//!
//! *** UNVERIFIED on a real Windows PC ***. Written without one, like `app_blocker`. If it never fires, check
//! in this order: (1) the address-bar lookup (`OmniboxViewViews` is Chromium's class name, `urlbar-input` is
//! Firefox's id), (2) that Chrome has built its UI Automation tree (it does so lazily on the first client),
//! (3) that `can_read()` is not false on the Devices screen.

use std::mem::ManuallyDrop;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;
use std::time::{Duration, Instant};

use now_focus_core::feed_url::{feed_for_url, CloseGate};
use tauri::{AppHandle, Manager};
use windows::core::BSTR;
use windows::Win32::Foundation::HWND as ComHwnd;
use windows::Win32::System::Com::{
    CoCreateInstance, CoInitializeEx, CLSCTX_INPROC_SERVER, COINIT_MULTITHREADED,
};
use windows::Win32::System::Variant::{VARIANT, VT_BSTR};
use windows::Win32::UI::Accessibility::{
    CUIAutomation, IUIAutomation, IUIAutomationCondition, IUIAutomationValuePattern,
    TreeScope_Descendants, UIA_AutomationIdPropertyId, UIA_ClassNamePropertyId, UIA_ValuePatternId,
    UIA_PROPERTY_ID,
};
use windows_sys::Win32::Foundation::HWND;
use windows_sys::Win32::UI::Input::KeyboardAndMouse::{
    GetAsyncKeyState, SendInput, INPUT, INPUT_0, INPUT_KEYBOARD, KEYBDINPUT, KEYEVENTF_KEYUP,
    VK_CONTROL, VK_LWIN, VK_MENU, VK_RWIN, VK_SHIFT,
};
use windows_sys::Win32::UI::WindowsAndMessaging::GetForegroundWindow;

use crate::commands::SharedState;

/// How often the front browser is looked at. Cheap while no browser is in front or no feed rule is live.
const POLL: Duration = Duration::from_millis(750);
/// Reads in a row that may fail before the Devices screen says the address bar can't be read.
const FAILS_BEFORE_UNHEALTHY: u32 = 5;
const VK_W: u16 = 0x57;

/// Browsers whose address bar this knows how to find. Vivaldi and Opera draw theirs differently: not listed.
const BROWSERS: [&str; 4] = ["chrome.exe", "msedge.exe", "brave.exe", "firefox.exe"];

/// False after several failed address reads while a feed rule was live; true again on the next good read or
/// when no rule is live (nothing depends on it then).
static READ_OK: AtomicBool = AtomicBool::new(true);

pub fn can_read() -> bool {
    READ_OK.load(Ordering::Relaxed)
}

/// Call once at startup. Runs for the life of the app on its own thread.
pub fn install(app: AppHandle) {
    thread::spawn(move || run(app));
}

enum Reading {
    Address(String),
    /// The address bar has keyboard focus: the user is typing, and what it shows may be half an address.
    Typing,
    Failed,
}

fn run(app: AppHandle) {
    // SAFETY: first COM call on this thread; the apartment lives as long as the thread.
    if unsafe { CoInitializeEx(None, COINIT_MULTITHREADED) }.is_err() {
        READ_OK.store(false, Ordering::Relaxed);
        return;
    }
    // SAFETY: CUIAutomation is the documented class for IUIAutomation, created in-process.
    let automation: IUIAutomation =
        match unsafe { CoCreateInstance(&CUIAutomation, None, CLSCTX_INPROC_SERVER) } {
            Ok(a) => a,
            Err(_) => {
                READ_OK.store(false, Ordering::Relaxed);
                return;
            }
        };
    // Built once: the address bar is "the Chromium omnibox class" or "Firefox's urlbar id".
    let (Some(chromium), Some(firefox)) = (
        property_condition(&automation, UIA_ClassNamePropertyId, "OmniboxViewViews"),
        property_condition(&automation, UIA_AutomationIdPropertyId, "urlbar-input"),
    ) else {
        READ_OK.store(false, Ordering::Relaxed);
        return;
    };
    let bars = AddressBars { chromium, firefox };
    let started = Instant::now();
    let mut gate = CloseGate::default();
    let mut fails = 0u32;

    loop {
        thread::sleep(POLL);
        // SAFETY: no arguments; returns the foreground window, or null.
        let hwnd = unsafe { GetForegroundWindow() };
        if hwnd.is_null() {
            continue;
        }
        let Some(exe) = crate::app_blocker::foreground_process_path(hwnd).map(|p| exe_name(&p))
        else {
            continue;
        };
        if !BROWSERS.contains(&exe.as_str()) {
            continue;
        }
        let live = {
            let state = app.state::<SharedState>();
            let Ok(mut guard) = state.lock() else {
                continue;
            };
            guard.live_feeds()
        };
        if live.is_empty() {
            fails = 0;
            READ_OK.store(true, Ordering::Relaxed);
            continue;
        }
        match read_address(&automation, &bars, hwnd, &exe) {
            Reading::Typing => {}
            Reading::Failed => {
                fails += 1;
                if fails >= FAILS_BEFORE_UNHEALTHY {
                    READ_OK.store(false, Ordering::Relaxed);
                }
            }
            Reading::Address(address) => {
                fails = 0;
                READ_OK.store(true, Ordering::Relaxed);
                let Some(key) = feed_for_url(&address) else {
                    continue;
                };
                let now_ms = started.elapsed().as_millis() as u64;
                if live.iter().any(|k| k == key) && gate.allow(now_ms) && close_tab(hwnd) {
                    let state = app.state::<SharedState>();
                    let _ = state.lock().map(|mut guard| guard.record_feed_block(key));
                }
            }
        }
    }
}

fn exe_name(path: &str) -> String {
    path.rsplit(['\\', '/'])
        .next()
        .unwrap_or(path)
        .to_lowercase()
}

struct AddressBars {
    chromium: IUIAutomationCondition,
    firefox: IUIAutomationCondition,
}

/// A condition on one string-valued UI Automation property. windows 0.61 has no safe VARIANT, so the BSTR
/// variant is built by hand and freed once the condition (which copies it) exists.
fn property_condition(
    automation: &IUIAutomation,
    property: UIA_PROPERTY_ID,
    text: &str,
) -> Option<IUIAutomationCondition> {
    // SAFETY: `v` is zeroed, then given a BSTR that this function owns and frees before returning.
    unsafe {
        let mut v = VARIANT::default();
        let inner = &mut *v.Anonymous.Anonymous;
        inner.vt = VT_BSTR;
        inner.Anonymous.bstrVal = ManuallyDrop::new(BSTR::from(text));
        let condition = automation.CreatePropertyCondition(property, &v).ok();
        ManuallyDrop::drop(&mut (*v.Anonymous.Anonymous).Anonymous.bstrVal);
        condition
    }
}

fn read_address(automation: &IUIAutomation, bars: &AddressBars, hwnd: HWND, exe: &str) -> Reading {
    // SAFETY: every call below is a plain COM call on objects this function just obtained; `hwnd` is the
    // current foreground window (it may vanish mid-call, which surfaces as an Err).
    unsafe {
        let Ok(window) = automation.ElementFromHandle(ComHwnd(hwnd)) else {
            return Reading::Failed;
        };
        let condition = if exe == "firefox.exe" {
            &bars.firefox
        } else {
            &bars.chromium
        };
        let Ok(bar) = window.FindFirst(TreeScope_Descendants, condition) else {
            return Reading::Failed;
        };
        if bar.CurrentHasKeyboardFocus().is_ok_and(|b| b.as_bool()) {
            return Reading::Typing;
        }
        let Ok(value) = bar.GetCurrentPatternAs::<IUIAutomationValuePattern>(UIA_ValuePatternId)
        else {
            return Reading::Failed;
        };
        match value.CurrentValue() {
            Ok(text) => Reading::Address(text.to_string()),
            Err(_) => Reading::Failed,
        }
    }
}

/// Presses Ctrl+W in `hwnd`, unless that window is no longer in front or the user is holding a modifier
/// (Ctrl+Shift+W closes the whole window). Returns whether the keys were sent.
fn close_tab(hwnd: HWND) -> bool {
    // SAFETY: `GetForegroundWindow` and `GetAsyncKeyState` take plain values; `SendInput` is given a valid
    // array and its element size.
    unsafe {
        if GetForegroundWindow() != hwnd {
            return false;
        }
        for vk in [VK_SHIFT, VK_MENU, VK_LWIN, VK_RWIN] {
            if GetAsyncKeyState(vk as i32) as u16 & 0x8000 != 0 {
                return false;
            }
        }
        let keys = [
            key(VK_CONTROL, false),
            key(VK_W, false),
            key(VK_W, true),
            key(VK_CONTROL, true),
        ];
        SendInput(
            keys.len() as u32,
            keys.as_ptr(),
            std::mem::size_of::<INPUT>() as i32,
        ) == keys.len() as u32
    }
}

fn key(vk: u16, up: bool) -> INPUT {
    INPUT {
        r#type: INPUT_KEYBOARD,
        Anonymous: INPUT_0 {
            ki: KEYBDINPUT {
                wVk: vk,
                wScan: 0,
                dwFlags: if up { KEYEVENTF_KEYUP } else { 0 },
                time: 0,
                dwExtraInfo: 0,
            },
        },
    }
}
