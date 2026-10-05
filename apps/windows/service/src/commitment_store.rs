//! *** UNVERIFIED — no Windows machine in this build environment ***
//!
//! Service-owned persistence + time-anchoring for the 14-day commitment, ported
//! from apps/macos/NowFocusDaemon/CommitmentStore.swift. The math
//! (grace/expiry/clock-change resistance) lives in the unit-tested
//! `now_focus_core::commitment`; this is the thin Win32 shell around it:
//!
//! - awake seconds via `QueryUnbiasedInterruptTime` (matches macOS
//!   `ProcessInfo.systemUptime`; `GetTickCount64` would include sleep),
//! - a clock-immune boot identity via the kernel `BootId` registry value (not
//!   `LastBootUpTime`, which an NTP correction can shift),
//! - state persisted as SYSTEM under `%ProgramData%\NowFocus`, locked down with
//!   `icacls` so a non-elevated user can't edit or delete it,
//! - the hosts-file commitment region driven through `network_enforcer`.
//!
//! Refusal to clear after the 60s grace is decided **here, in the service** —
//! the pipe ACL admits any interactive user, so a UI-side check is worthless.

use std::path::PathBuf;
use std::process::Command;
use std::sync::{Mutex, OnceLock};

use chrono::Utc;
use now_focus_core::commitment::CommitmentState;
use now_focus_ipc::CommitmentStatusWire;

use crate::network_enforcer;

fn state_dir() -> PathBuf {
    let base = std::env::var("ProgramData").unwrap_or_else(|_| r"C:\ProgramData".to_string());
    PathBuf::from(base).join("NowFocus")
}

fn state_path() -> PathBuf {
    state_dir().join("commitment_state.json")
}

/// Awake seconds since boot. `QueryUnbiasedInterruptTime` excludes sleep, which
/// matches macOS `ProcessInfo.systemUptime`. A failure returns 0; the boot-id
/// guard still gates whether this value is trusted.
fn now_uptime_secs() -> i64 {
    use windows_sys::Win32::System::WindowsProgramming::QueryUnbiasedInterruptTime;
    let mut t: u64 = 0;
    // SAFETY: `t` is a valid out-param; the API writes a u64 in 100ns units.
    unsafe {
        QueryUnbiasedInterruptTime(&mut t);
    }
    (t / 10_000_000) as i64
}

/// A boot identity a clock change cannot move: the kernel `BootId` increments
/// once per real boot. Read via `reg query` to keep the FFI surface thin.
/// Returns "unknown" when unavailable (see the module's must-verify note).
fn boot_id() -> String {
    let out = Command::new("reg")
        .args([
            "query",
            r"HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\Memory Management\PrefetchParameters",
            "/v",
            "BootId",
        ])
        .output();
    if let Ok(out) = out {
        let text = String::from_utf8_lossy(&out.stdout);
        if let Some(line) = text.lines().find(|l| l.contains("BootId")) {
            if let Some(val) = line.split_whitespace().last() {
                return val.to_string();
            }
        }
    }
    "unknown".to_string()
}

pub struct CommitmentStore {
    current: Option<CommitmentState>,
}

impl CommitmentStore {
    fn load() -> Self {
        let current = std::fs::read_to_string(state_path())
            .ok()
            .and_then(|s| serde_json::from_str::<CommitmentState>(&s).ok());
        Self { current }
    }

    fn persist(&self) {
        match &self.current {
            None => {
                let _ = std::fs::remove_file(state_path());
            }
            Some(state) => {
                let dir = state_dir();
                if std::fs::create_dir_all(&dir).is_err() {
                    eprintln!("failed to create commitment state dir");
                    return;
                }
                match serde_json::to_string(state) {
                    Ok(json) => {
                        if let Err(e) = std::fs::write(state_path(), json) {
                            eprintln!("failed to persist commitment state: {e}");
                            return;
                        }
                        lock_down_permissions(&dir);
                    }
                    Err(e) => eprintln!("failed to serialize commitment state: {e}"),
                }
            }
        }
    }
}

/// Remove inherited ACEs and grant full control only to SYSTEM (S-1-5-18) and
/// the Administrators group (S-1-5-32-544), so a standard user cannot edit or
/// delete the state file to unlock a commitment early. Uses well-known SIDs to
/// avoid locale-dependent account names.
fn lock_down_permissions(dir: &std::path::Path) {
    let status = Command::new("icacls")
        .arg(dir)
        .args([
            "/inheritance:r",
            "/grant:r",
            "*S-1-5-18:(OI)(CI)F",
            "/grant:r",
            "*S-1-5-32-544:(OI)(CI)F",
        ])
        .status();
    if let Err(e) = status {
        eprintln!("failed to lock down commitment state permissions: {e}");
    }
}

fn store() -> &'static Mutex<CommitmentStore> {
    static S: OnceLock<Mutex<CommitmentStore>> = OnceLock::new();
    S.get_or_init(|| Mutex::new(CommitmentStore::load()))
}

/// Start the commitment on these domains, freshly time-anchored. Refuses to
/// replace one that's still in effect (even during its grace window) — the
/// service, not the caller, enforces this: the pipe ACL admits any interactive
/// user, so an unguarded replace would let a throwaway commitment swap out the
/// real one and then be cancelled. Mirrors Android's createCommitmentShield.
pub fn apply(domains: Vec<String>) -> Result<(), String> {
    let mut guard = store().lock().map_err(|_| "commitment store poisoned")?;
    let now = Utc::now();
    let uptime = now_uptime_secs();
    let boot = boot_id();
    if !now_focus_core::commitment::can_replace(guard.current.as_ref(), now, uptime, &boot) {
        return Err(
            "A commitment is already in effect and can't be replaced until it ends.".to_string(),
        );
    }
    guard.current = Some(CommitmentState::new(domains.clone(), now, uptime, boot));
    guard.persist();
    network_enforcer::apply_commitment(&domains)
}

/// Clear only inside the 60s grace window; refuse otherwise (from the service).
pub fn clear_if_in_grace() -> Result<(), String> {
    let mut guard = store().lock().map_err(|_| "commitment store poisoned")?;
    match &guard.current {
        Some(state) if state.can_cancel(now_uptime_secs(), &boot_id()) => {
            guard.current = None;
            guard.persist();
            network_enforcer::clear_commitment()
        }
        Some(_) => Err("commitmentLocked".to_string()),
        None => Ok(()),
    }
}

pub fn status() -> Option<CommitmentStatusWire> {
    let guard = store().lock().ok()?;
    let state = guard.current.as_ref()?;
    let now = Utc::now();
    let uptime = now_uptime_secs();
    let boot = boot_id();
    Some(CommitmentStatusWire {
        domains: state.domains.clone(),
        end_at: state.end_at.to_rfc3339(),
        can_cancel_now: state.can_cancel(uptime, &boot),
        remaining_secs: state.remaining_secs(now, uptime, &boot),
    })
}

/// Clear a commitment whose 14 days have elapsed (the hourly timer + launch).
pub fn expire_if_needed() {
    let Ok(mut guard) = store().lock() else {
        return;
    };
    let over = guard
        .current
        .as_ref()
        .map(|s| s.is_over(Utc::now(), now_uptime_secs(), &boot_id()))
        .unwrap_or(false);
    if over {
        guard.current = None;
        guard.persist();
        let _ = network_enforcer::clear_commitment();
    }
}

/// At service start: drop an expired commitment, else re-assert its hosts-file
/// region (it may have been cleared while the service was down).
pub fn restore_on_launch() {
    expire_if_needed();
    let Ok(guard) = store().lock() else { return };
    match &guard.current {
        Some(state) => {
            let _ = network_enforcer::apply_commitment(&state.domains);
        }
        None => {
            let _ = network_enforcer::clear_commitment();
        }
    }
}

/// Clear the commitment's hosts-file region and delete its persisted state.
/// Called by the uninstaller via `now-focus-service.exe --cleanup` (see
/// main.rs) so removing NowFocus never leaves a permanent, unmanaged block
/// behind — nothing would expire it once the service is gone.
pub fn purge() {
    let _ = std::fs::remove_file(state_path());
    let _ = network_enforcer::clear_commitment();
}
