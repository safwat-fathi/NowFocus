//! Port of apps/macos/NowFocusDaemon/NetworkEnforcer.swift. Windows'
//! `C:\Windows\System32\drivers\etc\hosts` instead of `/etc/hosts`,
//! `ipconfig /flushdns` instead of `killall -HUP mDNSResponder` +
//! `dscacheutil -flushcache` — same marker-delimited edit, same
//! re-validate-at-the-write-boundary discipline (commit d0dd7df), same
//! www./m./mobile. subdomain-prefix limitation (hosts files don't support
//! wildcards on either platform).
//!
//! This is a fail-safe mechanism deliberately, not a live DNS proxy: if this
//! service crashes, the hosts file is left exactly as last written — stale,
//! but the user still has working DNS. A live proxy that repoints the
//! adapter's resolver would fail the user's whole internet connection if the
//! service died. See the plan's "fail-safe website-blocking mechanism" note.

use std::fs;
use std::path::PathBuf;
use std::process::Command;

use now_focus_core::{domain_validation, BlockPolicy};

const START_MARKER: &str = "### FOCUS APP BLOCK START ###";
const END_MARKER: &str = "### FOCUS APP BLOCK END ###";

fn hosts_path() -> PathBuf {
    let system_root = std::env::var("SystemRoot").unwrap_or_else(|_| r"C:\Windows".to_string());
    PathBuf::from(system_root).join(r"System32\drivers\etc\hosts")
}

fn backup_path() -> PathBuf {
    let mut p = hosts_path();
    p.set_file_name("hosts.nowfocus.backup");
    p
}

pub fn apply(policy: &BlockPolicy) -> Result<(), String> {
    let mut content = read_hosts()?;
    content = remove_block(&content);

    if !policy.domains.is_empty() {
        let mut block = format!("{START_MARKER}\n");
        for rule in policy.domains.iter().filter(|d| d.enabled) {
            // Trust boundary: the pipe client isn't strongly authenticated
            // (see pipe_server's ACL comment), so re-validate here even
            // though the UI already did — this writes to the system hosts
            // file. Same lesson as macOS commit d0dd7df.
            let Some(domain) = domain_validation::normalize(&rule.domain) else {
                eprintln!("Skipping invalid domain in policy: {}", rule.domain);
                continue;
            };
            block.push_str(&format!("127.0.0.1 {domain}\n"));
            if rule.include_subdomains {
                for prefix in ["www.", "m.", "mobile."] {
                    block.push_str(&format!("127.0.0.1 {prefix}{domain}\n"));
                }
            }
        }
        block.push_str(&format!("{END_MARKER}\n"));
        content.push('\n');
        content.push_str(&block);
    }

    write_hosts(&content)?;
    flush_dns_cache();
    Ok(())
}

pub fn clear() -> Result<(), String> {
    let content = read_hosts()?;
    write_hosts(&remove_block(&content))?;
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

fn remove_block(content: &str) -> String {
    let mut inside = false;
    let mut out = Vec::new();
    for line in content.lines() {
        if line == START_MARKER {
            inside = true;
            continue;
        }
        if line == END_MARKER {
            inside = false;
            continue;
        }
        if !inside {
            out.push(line);
        }
    }
    out.join("\n").trim().to_string()
}

fn flush_dns_cache() {
    if let Err(e) = Command::new("ipconfig").arg("/flushdns").status() {
        eprintln!("Failed to flush DNS cache with ipconfig /flushdns: {e}");
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn remove_block_strips_only_the_marked_region() {
        let content = "127.0.0.1 localhost\n\n### FOCUS APP BLOCK START ###\n127.0.0.1 youtube.com\n### FOCUS APP BLOCK END ###\n";
        assert_eq!(remove_block(content), "127.0.0.1 localhost");
    }

    #[test]
    fn remove_block_is_a_no_op_when_no_block_present() {
        let content = "127.0.0.1 localhost";
        assert_eq!(remove_block(content), "127.0.0.1 localhost");
    }

    #[test]
    fn remove_block_then_reapply_does_not_duplicate() {
        let base = "127.0.0.1 localhost";
        let with_block = format!("{base}\n\n{START_MARKER}\n127.0.0.1 x.com\n{END_MARKER}\n");
        let cleared = remove_block(&with_block);
        assert_eq!(cleared, base);
        // Simulate applying twice in a row: second apply must not stack markers.
        let reapplied = format!("{cleared}\n\n{START_MARKER}\n127.0.0.1 y.com\n{END_MARKER}\n");
        let cleared_again = remove_block(&reapplied);
        assert_eq!(cleared_again, base);
    }
}
