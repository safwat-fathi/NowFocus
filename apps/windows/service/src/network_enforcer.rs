//! Port of apps/macos/NowFocusDaemon/NetworkEnforcer.swift. Windows'
//! `C:\Windows\System32\drivers\etc\hosts` instead of `/etc/hosts`,
//! `ipconfig /flushdns` instead of `killall -HUP mDNSResponder`. Same
//! marker-delimited edit, same re-validate-at-the-write-boundary discipline
//! (commit d0dd7df), same www./m./mobile. subdomain-prefix limitation.
//!
//! Two **independent** marked regions — session and commitment — exactly like
//! macOS's `HostsFileMarkers` split. Each apply/clear reads the file fresh and
//! rewrites only its own region (via the unit-tested `core::hosts_block`), so a
//! focus session ending never wipes a live 14-day commitment, and vice-versa.
//! That independence is the property `core::hosts_block`'s tests prove.
//!
//! Fail-safe by design, not a live proxy: if this service crashes, the hosts
//! file is left exactly as last written — stale, but DNS still works.

use std::fs;
use std::path::PathBuf;
use std::process::Command;

use now_focus_core::{allowlist, domain_validation, hosts_block, BlockPolicy};

const SESSION_START: &str = "### FOCUS APP BLOCK START ###";
const SESSION_END: &str = "### FOCUS APP BLOCK END ###";
const COMMITMENT_START: &str = "### FOCUS COMMITMENT BLOCK START ###";
const COMMITMENT_END: &str = "### FOCUS COMMITMENT BLOCK END ###";

fn hosts_path() -> PathBuf {
    let system_root = std::env::var("SystemRoot").unwrap_or_else(|_| r"C:\Windows".to_string());
    PathBuf::from(system_root).join(r"System32\drivers\etc\hosts")
}

fn backup_path() -> PathBuf {
    let mut p = hosts_path();
    p.set_file_name("hosts.nowfocus.backup");
    p
}

// ---- Session block ----------------------------------------------------

pub fn apply(policy: &BlockPolicy) -> Result<(), String> {
    // `blocked_domains` is empty for an allowlist: every caller routes through here, so this is the one place
    // that keeps an allowlist's domains from being written as blocks.
    let lines = domain_lines(allowlist::blocked_domains(policy));
    rewrite_region(SESSION_START, SESSION_END, &lines)
}

pub fn clear() -> Result<(), String> {
    rewrite_region(SESSION_START, SESSION_END, &[])
}

// ---- Commitment block (independent of the session block) --------------

pub fn apply_commitment(domains: &[String]) -> Result<(), String> {
    let lines = domain_lines(domains.iter().map(|d| (d.as_str(), true)));
    rewrite_region(COMMITMENT_START, COMMITMENT_END, &lines)
}

pub fn clear_commitment() -> Result<(), String> {
    rewrite_region(COMMITMENT_START, COMMITMENT_END, &[])
}

// ---- Internals --------------------------------------------------------

/// Trust boundary: the pipe client isn't strongly authenticated (see
/// pipe_server's ACL comment), so re-validate every domain here even though the
/// UI already did — this writes to the system hosts file. Invalid entries are
/// skipped and logged, never written (same lesson as macOS commit d0dd7df).
fn domain_lines<'a>(domains: impl Iterator<Item = (&'a str, bool)>) -> Vec<String> {
    let mut lines = Vec::new();
    for (raw, include_subdomains) in domains {
        let Some(domain) = domain_validation::normalize(raw) else {
            eprintln!("Skipping invalid domain in policy: {raw}");
            continue;
        };
        lines.push(format!("127.0.0.1 {domain}"));
        if include_subdomains {
            for prefix in ["www.", "m.", "mobile."] {
                lines.push(format!("127.0.0.1 {prefix}{domain}"));
            }
        }
    }
    lines
}

fn rewrite_region(start: &str, end: &str, lines: &[String]) -> Result<(), String> {
    let content = read_hosts()?;
    let updated = hosts_block::with_region(&content, start, end, lines);
    write_hosts(&updated)?;
    flush_dns_cache();
    Ok(())
}

fn read_hosts() -> Result<String, String> {
    fs::read_to_string(hosts_path()).map_err(|e| format!("failed to read hosts file: {e}"))
}

fn write_hosts(content: &str) -> Result<(), String> {
    let path = hosts_path();
    let backup = backup_path();
    if !backup.exists() {
        fs::copy(&path, &backup).map_err(|e| format!("failed to back up hosts file: {e}"))?;
    }

    // Write-then-rename for an atomic replace, same intent as the Swift
    // version's `atomically: true` — never leave the hosts file half-written.
    let tmp = path.with_extension("nowfocus-tmp");
    fs::write(&tmp, content).map_err(|e| format!("failed to write temporary hosts file: {e}"))?;
    fs::rename(&tmp, &path).map_err(|e| format!("failed to replace hosts file: {e}"))
}

fn flush_dns_cache() {
    if let Err(e) = Command::new("ipconfig").arg("/flushdns").status() {
        eprintln!("Failed to flush DNS cache with ipconfig /flushdns: {e}");
    }
}
