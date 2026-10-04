//! Whitelist mode ("allow only these apps") and the exe matching both modes share. Pure, so `cargo test`
//! covers the decisions on any host; `src-tauri` supplies the live inputs (own exe, `%SystemRoot%`).
//!
//! Apps only: an allowlist's domains are never enforced (a hosts file can't say "everything except"), and
//! an allowlist with no enabled Windows app rules enforces nothing, because its apps may all be another
//! platform's (the list syncs) and closing everything would be the worst way to find that out.

use crate::block_policy::{ApplicationRule, BlockPolicy, PolicyMode};

const PLATFORM: &str = "windows";

/// Lowercased with `\` separators, and a versioned install folder (`app-1.0.9035`, Squirrel's layout for
/// Discord, Slack, Teams, Postman) as `app-*`, so an auto-update doesn't turn a listed app into a stranger.
fn normalize(path: &str) -> String {
    path.replace('/', "\\")
        .to_lowercase()
        .split('\\')
        .map(|seg| if is_versioned(seg) { "app-*" } else { seg })
        .collect::<Vec<_>>()
        .join("\\")
}

fn is_versioned(seg: &str) -> bool {
    seg.strip_prefix("app-").is_some_and(|v| {
        v.starts_with(|c: char| c.is_ascii_digit())
            && v.chars().all(|c| c.is_ascii_digit() || c == '.')
    })
}

/// Whether two exe paths are the same app. One comparator for both modes, so a blocklisted Discord doesn't
/// slip out of the list after its next update either.
pub fn same_exe(a: &str, b: &str) -> bool {
    normalize(a) == normalize(b)
}

/// The enabled app rules that apply on this machine.
pub fn windows_apps(policy: &BlockPolicy) -> impl Iterator<Item = &ApplicationRule> {
    policy
        .applications
        .iter()
        .filter(|a| a.enabled && a.platform == PLATFORM)
}

/// The domains a session blocks through the hosts file, with whether subdomains go too. None for an
/// allowlist: its domains (if any) are the ones to keep reachable, and a hosts file can't say "everything
/// except", so blocking them would do the opposite of the list.
pub fn blocked_domains(policy: &BlockPolicy) -> impl Iterator<Item = (&str, bool)> {
    policy
        .domains
        .iter()
        .filter(|d| d.enabled && policy.mode == PolicyMode::Blocklist)
        .map(|d| (d.domain.as_str(), d.include_subdomains))
}

/// False for an allowlist with no Windows app to allow: it must not start, join, or close anything here.
pub fn enforces_here(policy: &BlockPolicy) -> bool {
    policy.mode != PolicyMode::Allowlist || windows_apps(policy).next().is_some()
}

fn under(path: &str, dir: &str) -> bool {
    let p = path.replace('/', "\\").to_lowercase();
    let d = dir.replace('/', "\\").to_lowercase();
    p.strip_prefix(d.trim_end_matches('\\'))
        .is_some_and(|rest| rest.starts_with('\\'))
}

/// Never closed in whitelist mode: NowFocus itself (its Shield is a window of its own process, so closing
/// it would close the screen that explains the block) and everything under `%SystemRoot%`.
/// A name list would miss the helpers an allowed app launches (conhost, OpenWith, PickerHost, CredentialUIBroker,
/// smartscreen, WerFault...) and close a dialog in the middle of a flow. The cost: Notepad, cmd, PowerShell and
/// mstsc are always allowed, and so is ApplicationFrameHost, the process every UWP/Store app shows up as.
/// ponytail: Store apps can neither be allowed nor closed; resolve the hosted child window's process if that matters.
pub fn always_allowed(exe: &str, own_exe: Option<&str>, system_root: &str) -> bool {
    own_exe.is_some_and(|own| same_exe(own, exe)) || under(exe, system_root)
}

/// Whether whitelist mode closes `exe`. Blocklists are decided by their own rules, not here.
pub fn closes(policy: &BlockPolicy, exe: &str, own_exe: Option<&str>, system_root: &str) -> bool {
    policy.mode == PolicyMode::Allowlist
        && enforces_here(policy)
        && !always_allowed(exe, own_exe, system_root)
        && !windows_apps(policy).any(|a| same_exe(&a.native_identifier, exe))
}

/// What the Shield calls an app the list has no name for: the file name without `.exe`.
pub fn display_name(exe: &str) -> String {
    let file = exe.rsplit(['\\', '/']).next().unwrap_or(exe);
    match file.rsplit_once('.') {
        Some((stem, ext)) if ext.eq_ignore_ascii_case("exe") && !stem.is_empty() => {
            stem.to_string()
        }
        _ => file.to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const ROOT: &str = r"C:\Windows";
    const OWN: &str = r"C:\Program Files\NowFocus\NowFocus.exe";

    fn policy(mode: PolicyMode, apps: &[&str]) -> BlockPolicy {
        let mut p = BlockPolicy::new("t");
        p.mode = mode;
        p.applications = apps.iter().map(|a| ApplicationRule::new(*a, "x")).collect();
        p
    }

    #[test]
    fn exe_paths_match_ignoring_case_and_slash_style() {
        assert!(same_exe(r"C:\Apps\Chat.exe", r"c:/apps/chat.EXE"));
        assert!(!same_exe(r"C:\Apps\Chat.exe", r"C:\Apps\Other.exe"));
    }

    #[test]
    fn an_auto_updated_versioned_folder_is_still_the_same_app() {
        let old = r"C:\Users\me\AppData\Local\Discord\app-1.0.9035\Discord.exe";
        let new = r"C:\Users\me\AppData\Local\Discord\app-1.0.9036\Discord.exe";
        assert!(same_exe(old, new));
        assert!(!same_exe(
            old,
            r"C:\Users\me\AppData\Local\Slack\app-1.0.9036\Discord.exe"
        ));
        // Only a version-shaped folder is folded: a folder that merely starts with "app-" is a different place.
        assert!(!same_exe(r"C:\x\app-beta\a.exe", r"C:\x\app-gamma\a.exe"));
        assert!(!same_exe(r"C:\x\app-\a.exe", r"C:\x\app-1\a.exe"));
    }

    #[test]
    fn an_allowlist_closes_what_it_does_not_list_and_keeps_what_it_does() {
        let p = policy(PolicyMode::Allowlist, &[r"C:\Apps\Code.exe"]);
        assert!(closes(&p, r"C:\Apps\Game.exe", Some(OWN), ROOT));
        assert!(!closes(&p, r"c:\apps\code.exe", Some(OWN), ROOT));
    }

    #[test]
    fn a_blocklist_is_never_decided_here() {
        let p = policy(PolicyMode::Blocklist, &[r"C:\Apps\Code.exe"]);
        assert!(!closes(&p, r"C:\Apps\Game.exe", Some(OWN), ROOT));
    }

    #[test]
    fn nowfocus_and_the_windows_directory_are_never_closed() {
        let p = policy(PolicyMode::Allowlist, &[r"C:\Apps\Code.exe"]);
        assert!(!closes(&p, OWN, Some(OWN), ROOT));
        assert!(!closes(&p, r"C:\Windows\explorer.exe", Some(OWN), ROOT));
        assert!(!closes(
            &p,
            r"C:\Windows\System32\conhost.exe",
            Some(OWN),
            ROOT
        ));
        assert!(!closes(
            &p,
            r"c:\WINDOWS\SystemApps\ShellExperienceHost\x.exe",
            Some(OWN),
            ROOT
        ));
    }

    #[test]
    fn a_lookalike_outside_the_windows_directory_is_not_exempt() {
        let p = policy(PolicyMode::Allowlist, &[r"C:\Apps\Code.exe"]);
        assert!(closes(&p, r"C:\Users\me\explorer.exe", Some(OWN), ROOT));
        assert!(closes(&p, r"C:\Windows.old\x.exe", Some(OWN), ROOT));
        assert!(closes(&p, r"C:\WindowsApps\x.exe", Some(OWN), ROOT));
    }

    #[test]
    fn an_allowlist_with_no_windows_app_enforces_nothing() {
        let empty = policy(PolicyMode::Allowlist, &[]);
        assert!(!enforces_here(&empty));
        assert!(!closes(&empty, r"C:\Apps\Game.exe", Some(OWN), ROOT));

        let mut other_platform = policy(PolicyMode::Allowlist, &[r"C:\Apps\Code.exe"]);
        other_platform.applications[0].platform = "android".into();
        assert!(!enforces_here(&other_platform));

        let mut disabled = policy(PolicyMode::Allowlist, &[r"C:\Apps\Code.exe"]);
        disabled.applications[0].enabled = false;
        assert!(!enforces_here(&disabled));

        assert!(enforces_here(&policy(PolicyMode::Blocklist, &[])));
        assert!(enforces_here(&policy(
            PolicyMode::Allowlist,
            &[r"C:\Apps\Code.exe"]
        )));
    }

    #[test]
    fn only_a_blocklist_writes_its_domains_to_the_hosts_file() {
        use crate::block_policy::DomainRule;
        let mut p = policy(PolicyMode::Blocklist, &[]);
        p.domains.push(DomainRule::new("example.com"));
        let mut off = DomainRule::new("off.com");
        off.enabled = false;
        p.domains.push(off);
        assert_eq!(p.domains.len(), 2);
        assert_eq!(
            blocked_domains(&p).collect::<Vec<_>>(),
            vec![("example.com", true)]
        );

        p.mode = PolicyMode::Allowlist;
        assert_eq!(
            blocked_domains(&p).count(),
            0,
            "an allowlist's domains are never blocked"
        );
    }

    #[test]
    fn the_shield_names_an_unlisted_app_by_its_file() {
        assert_eq!(display_name(r"C:\Games\Steam\steam.EXE"), "steam");
        assert_eq!(display_name("tool"), "tool");
        assert_eq!(display_name(r"C:\x\data.bin"), "data.bin");
    }
}
