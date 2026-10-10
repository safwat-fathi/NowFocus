//! Windows-only. Points the physical adapters' DNS at the resolver the user chose in Settings while a session runs,
//! and puts it back. All the text it runs (the PowerShell) and all the checking of what it is told comes from
//! `core::dns_resolvers`, which is unit-tested on any host; this file only shells out and keeps the backup.
//!
//! The backup (`dns_backup.json` next to the commitment state, same locked-down folder) is written once, before the
//! first change, and never overwritten by a second `apply`, or the already-filtered DNS would become "the original".
//! Adapters that show up later (Wi-Fi to Ethernet) are added to it the next time `apply` runs: the service re-applies
//! every 120s while it holds a DNS (`reassert`). It is only deleted after a successful restore, so a failed one is retried.
//!
//! `dns_state.json` (same folder) is the service's own memory of what it applied and whether the user keeps it on
//! outside sessions ("always on"). What to do at each event (`ClearDns`, release, start, stop) is decided by the
//! tested functions in `core::dns_resolvers`; this file runs the plan.
//!
//! *** UNVERIFIED — no Windows machine in this build environment ***, same caveat as pipe_server and
//! network_enforcer: the PowerShell cmdlets are from documentation, not run.

use std::fs;
use std::io::Write;
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::sync::Mutex;

use now_focus_core::dns_resolvers::{self, AdapterDns, DnsPlan, DnsState};

/// Apply and restore must not interleave: the pipe serves one client at a time, but the service's own startup and
/// shutdown restore run outside it.
static BUSY: Mutex<()> = Mutex::new(());

fn backup_path() -> PathBuf {
    crate::commitment_store::state_dir().join("dns_backup.json")
}

/// Absolute path, never a PATH lookup: this runs as SYSTEM, and a bare name would let a planted `powershell.exe`
/// (the current directory, a writable PATH entry) run with SYSTEM rights.
fn powershell_exe() -> PathBuf {
    let root = std::env::var("SystemRoot").unwrap_or_else(|_| r"C:\Windows".to_string());
    PathBuf::from(root).join(r"System32\WindowsPowerShell\v1.0\powershell.exe")
}

fn powershell(script: &str) -> Result<String, String> {
    let mut child = Command::new(powershell_exe())
        .args([
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-Command",
            "-",
        ])
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|e| format!("dns|could not start powershell: {e}"))?;
    child
        .stdin
        .take()
        .ok_or("dns|powershell has no stdin")?
        .write_all(script.as_bytes())
        .map_err(|e| format!("dns|could not send the script: {e}"))?;
    let out = child
        .wait_with_output()
        .map_err(|e| format!("dns|powershell failed: {e}"))?;
    if !out.status.success() {
        return Err(format!(
            "dns|{}",
            String::from_utf8_lossy(&out.stderr).trim()
        ));
    }
    Ok(String::from_utf8_lossy(&out.stdout).into_owned())
}

fn list_adapters() -> Result<Vec<AdapterDns>, String> {
    dns_resolvers::parse_adapters(&powershell(dns_resolvers::LIST_ADAPTERS_SCRIPT)?)
}

fn read_backup() -> Option<Vec<AdapterDns>> {
    serde_json::from_slice(&fs::read(backup_path()).ok()?).ok()
}

fn write_backup(adapters: &[AdapterDns]) -> Result<(), String> {
    let dir = crate::commitment_store::state_dir();
    fs::create_dir_all(&dir).map_err(|e| format!("dns|could not create the state folder: {e}"))?;
    let json = serde_json::to_vec(adapters).map_err(|e| e.to_string())?;
    let tmp = dir.join("dns_backup.json.tmp");
    fs::write(&tmp, json).map_err(|e| format!("dns|could not save the DNS backup: {e}"))?;
    fs::rename(&tmp, backup_path())
        .map_err(|e| format!("dns|could not save the DNS backup: {e}"))?;
    crate::commitment_store::lock_down_permissions(&dir);
    Ok(())
}

fn state_path() -> PathBuf {
    crate::commitment_store::state_dir().join("dns_state.json")
}

pub fn read_state() -> Option<DnsState> {
    serde_json::from_slice(&fs::read(state_path()).ok()?).ok()
}

fn write_state(state: &DnsState) -> Result<(), String> {
    let dir = crate::commitment_store::state_dir();
    fs::create_dir_all(&dir).map_err(|e| format!("dns|could not create the state folder: {e}"))?;
    let json = serde_json::to_vec(state).map_err(|e| e.to_string())?;
    let tmp = dir.join("dns_state.json.tmp");
    fs::write(&tmp, json).map_err(|e| format!("dns|could not save the DNS state: {e}"))?;
    fs::rename(&tmp, state_path()).map_err(|e| format!("dns|could not save the DNS state: {e}"))?;
    crate::commitment_store::lock_down_permissions(&dir);
    Ok(())
}

/// A session applies [servers]. Idempotent; the keep flag is whatever it already was.
pub fn apply(servers: &[String]) -> Result<(), String> {
    let next = DnsState::after_session_apply(read_state().as_ref(), servers);
    apply_state(&next)
}

/// The user keeps (`Some`) or stops keeping (`None`) this DNS outside sessions.
pub fn set_keep(servers: Option<&[String]>) -> Result<(), String> {
    match servers {
        Some(servers) => apply_state(&DnsState::keeping(servers)),
        None => run(dns_resolvers::on_release(read_state().as_ref())),
    }
}

/// A session ended.
pub fn clear() -> Result<(), String> {
    run(dns_resolvers::on_clear(read_state().as_ref()))
}

/// The service is starting: re-apply a kept DNS, undo anything else left behind.
pub fn on_start() -> Result<(), String> {
    run(dns_resolvers::on_start(read_state().as_ref()))
}

/// The service is stopping (Stop, Shutdown): a kept DNS stays so an upgrade does not drop the filter.
pub fn on_shutdown() -> Result<(), String> {
    run(dns_resolvers::on_shutdown(read_state().as_ref()))
}

/// Uninstall: undo it whatever the user's setting, or nothing would ever undo it.
pub fn cleanup() -> Result<(), String> {
    let result = restore();
    let _ = fs::remove_file(state_path());
    result
}

/// Every 120s while a DNS is held: pick up adapters that appeared (Wi-Fi to Ethernet, a dock, a USB adapter).
pub fn reassert() {
    if let Some(state) = read_state() {
        if let Err(e) = apply_state(&state) {
            eprintln!("dns re-assert failed: {e}");
        }
    }
}

fn run(plan: DnsPlan) -> Result<(), String> {
    match plan {
        DnsPlan::Nothing => Ok(()),
        DnsPlan::Restore => restore(),
        DnsPlan::Apply(servers) => apply_state(&DnsState::keeping(&servers)),
    }
}

fn apply_state(state: &DnsState) -> Result<(), String> {
    // Anyone who can reach the pipe can send this, so the addresses are checked here, not trusted.
    let ips = dns_resolvers::validate_servers(&state.servers)?;
    let _guard = BUSY.lock().unwrap_or_else(|p| p.into_inner());
    let adapters = list_adapters()?;
    if adapters.is_empty() {
        return Err("dnsNoAdapter".to_string());
    }
    let mut saved = read_backup().unwrap_or_default();
    let before = saved.len();
    for a in &adapters {
        if !saved.iter().any(|s| s.guid == a.guid) {
            saved.push(a.clone());
        }
    }
    if saved.len() != before {
        write_backup(&saved)?;
    }
    // The state is written before the change, so a crash in between is retried by the re-assert, not forgotten.
    write_state(state)?;
    powershell(&dns_resolvers::apply_script(&ips, &adapters)).map(|_| ())
}

/// Idempotent: nothing to do when no backup exists. Also forgets the state, so the re-assert stops.
fn restore() -> Result<(), String> {
    let _guard = BUSY.lock().unwrap_or_else(|p| p.into_inner());
    let path = backup_path();
    if !path.exists() {
        let _ = fs::remove_file(state_path());
        return Ok(());
    }
    let Some(saved) = read_backup() else {
        // Unreadable: nothing safe to write back. Drop it so a bad file can't wedge every later restore.
        let _ = fs::remove_file(&path);
        let _ = fs::remove_file(state_path());
        return Err("dns|the DNS backup was unreadable".to_string());
    };
    powershell(&dns_resolvers::restore_script(&saved))?;
    let _ = fs::remove_file(&path);
    let _ = fs::remove_file(state_path());
    Ok(())
}
