//! "DNS during a session": the resolver a desktop app points the system at while a focus session runs.
//!
//! Android forwards lookups itself (apps/android DnsUpstream.kt). A desktop has no tunnel, only a hosts file, so
//! here the privileged service changes the DNS servers of the physical adapters for the length of the session and
//! puts them back afterwards. The presets are the same plain-DNS addresses Android bootstraps with, so a family
//! filter (ads, adult content) keeps working next to NowFocus's own blocklist.
//!
//! Everything platform-neutral lives here so it is tested on any host: choosing the servers, validating them (the
//! service re-validates, since any local user can reach its pipe), reading the adapter list PowerShell prints, and
//! building the PowerShell that applies and restores. The service only runs the text this module produces.
//!
//! ponytail: presets are IPv4 only (the IPv6 twins could not be verified from a machine without IPv6). An adapter
//! that also has IPv6 DNS from the router can still answer from there; the hosts file blocks regardless.

use std::net::IpAddr;

use serde::{Deserialize, Serialize};

pub const MAX_SERVERS: usize = 4;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DnsProvider {
    /// Leave the adapters alone (the default).
    System,
    CloudflareFamily,
    AdguardFamily,
    CleanbrowsingFamily,
    Quad9,
    /// Addresses the user typed.
    Custom,
}

impl DnsProvider {
    pub const ALL: [DnsProvider; 6] = [
        DnsProvider::System,
        DnsProvider::CloudflareFamily,
        DnsProvider::AdguardFamily,
        DnsProvider::CleanbrowsingFamily,
        DnsProvider::Quad9,
        DnsProvider::Custom,
    ];

    /// The id stored on the device and sent to the UI.
    pub fn id(self) -> &'static str {
        match self {
            DnsProvider::System => "system",
            DnsProvider::CloudflareFamily => "cloudflare_family",
            DnsProvider::AdguardFamily => "adguard_family",
            DnsProvider::CleanbrowsingFamily => "cleanbrowsing_family",
            DnsProvider::Quad9 => "quad9",
            DnsProvider::Custom => "custom",
        }
    }

    pub fn from_id(id: &str) -> Option<DnsProvider> {
        DnsProvider::ALL.into_iter().find(|p| p.id() == id)
    }

    fn preset(self) -> &'static [&'static str] {
        match self {
            DnsProvider::CloudflareFamily => &["1.1.1.3", "1.0.0.3"],
            DnsProvider::AdguardFamily => &["94.140.14.15", "94.140.15.16"],
            DnsProvider::CleanbrowsingFamily => &["185.228.168.168", "185.228.169.168"],
            DnsProvider::Quad9 => &["9.9.9.9", "149.112.112.112"],
            DnsProvider::System | DnsProvider::Custom => &[],
        }
    }
}

/// The servers to apply for a stored choice. Empty means "do not touch the adapters": the System choice, and a
/// Custom choice whose text is not valid (a half-typed list must never reach an adapter).
pub fn servers_for(provider: DnsProvider, custom: &str) -> Vec<String> {
    match provider {
        DnsProvider::System => Vec::new(),
        DnsProvider::Custom => parse_servers(custom)
            .map(|ips| ips.into_iter().map(|ip| ip.to_string()).collect())
            .unwrap_or_default(),
        p => p.preset().iter().map(|s| s.to_string()).collect(),
    }
}

/// 1 to [`MAX_SERVERS`] addresses separated by commas, semicolons or spaces. Error is a bare code the UI translates.
pub fn parse_servers(raw: &str) -> Result<Vec<IpAddr>, String> {
    let mut out: Vec<IpAddr> = Vec::new();
    for token in raw
        .split(|c: char| c == ',' || c == ';' || c.is_whitespace())
        .filter(|t| !t.is_empty())
    {
        let ip: IpAddr = token.parse().map_err(|_| "dnsInvalid".to_string())?;
        if !usable(ip) {
            return Err("dnsInvalid".to_string());
        }
        if !out.contains(&ip) {
            out.push(ip);
        }
    }
    if out.is_empty() || out.len() > MAX_SERVERS {
        return Err("dnsInvalid".to_string());
    }
    Ok(out)
}

/// What the service accepts off the pipe: same rules as a typed list, for already-split strings.
pub fn validate_servers(servers: &[String]) -> Result<Vec<IpAddr>, String> {
    parse_servers(&servers.join(","))
}

/// Loopback and private ranges stay allowed (a Pi-hole or a local resolver is a normal choice). Link-local IPv6 is
/// refused: it needs a scope id to mean anything, and an unscoped one would break the adapter's DNS.
fn usable(ip: IpAddr) -> bool {
    if ip.is_unspecified() || ip.is_multicast() {
        return false;
    }
    match ip {
        IpAddr::V4(v4) => !v4.is_broadcast(),
        IpAddr::V6(v6) => (v6.segments()[0] & 0xffc0) != 0xfe80,
    }
}

/// One physical adapter's own (static) DNS, as found in the registry. Empty strings mean "from DHCP".
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AdapterDns {
    pub index: u32,
    pub guid: String,
    #[serde(default)]
    pub v4: String,
    #[serde(default)]
    pub v6: String,
}

impl AdapterDns {
    fn static_servers(&self) -> Vec<IpAddr> {
        let mut out = Vec::new();
        for token in self.v4.split([',', ' ']).chain(self.v6.split([',', ' '])) {
            if let Ok(ip) = token.trim().parse::<IpAddr>() {
                out.push(ip);
            }
        }
        out
    }
}

/// PowerShell run by the service to list the adapters worth touching: physical ones that are up. Virtual and VPN
/// adapters are left alone. Prints compact JSON.
pub const LIST_ADAPTERS_SCRIPT: &str = r#"$ErrorActionPreference='Stop'
$r = foreach ($a in Get-NetAdapter -Physical | Where-Object Status -eq 'Up') {
  $g = "$($a.InterfaceGuid)"
  $v4 = (Get-ItemProperty "HKLM:\SYSTEM\CurrentControlSet\Services\Tcpip\Parameters\Interfaces\$g" -ErrorAction SilentlyContinue).NameServer
  $v6 = (Get-ItemProperty "HKLM:\SYSTEM\CurrentControlSet\Services\Tcpip6\Parameters\Interfaces\$g" -ErrorAction SilentlyContinue).NameServer
  [pscustomobject]@{ index = [uint32]$a.ifIndex; guid = $g; v4 = "$v4"; v6 = "$v6" }
}
ConvertTo-Json -InputObject @($r) -Compress"#;

/// Reads [`LIST_ADAPTERS_SCRIPT`]'s output. Tolerates what ConvertTo-Json does with nothing to list (`[null]`, `null`
/// or an empty string) and with a single adapter (an array of one).
pub fn parse_adapters(json: &str) -> Result<Vec<AdapterDns>, String> {
    let text = json.trim();
    if text.is_empty() || text == "null" {
        return Ok(Vec::new());
    }
    let rows: Vec<Option<AdapterDns>> =
        serde_json::from_str(text).map_err(|e| format!("adapter list: {e}"))?;
    let adapters: Vec<AdapterDns> = rows.into_iter().flatten().collect();
    if adapters.iter().any(|a| !valid_guid(&a.guid)) {
        return Err("adapter list: unexpected interface id".to_string());
    }
    Ok(adapters)
}

/// `{xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx}`. Checked before a guid is put into a script.
fn valid_guid(guid: &str) -> bool {
    guid.len() == 38
        && guid.starts_with('{')
        && guid.ends_with('}')
        && guid[1..37]
            .chars()
            .all(|c| c.is_ascii_hexdigit() || c == '-')
}

/// Points every listed adapter at [servers]. The adapters must come from a listing taken just before, so the
/// interface indexes are current.
pub fn apply_script(servers: &[IpAddr], adapters: &[AdapterDns]) -> String {
    let list = servers
        .iter()
        .map(|ip| format!("'{ip}'"))
        .collect::<Vec<_>>()
        .join(",");
    let mut script = String::from("$ErrorActionPreference='Stop'\n");
    for a in adapters {
        script.push_str(&format!(
            "Set-DnsClientServerAddress -InterfaceIndex {} -ServerAddresses ({list})\n",
            a.index
        ));
    }
    script.push_str("& \"$env:SystemRoot\\System32\\ipconfig.exe\" /flushdns | Out-Null\n");
    script
}

/// Puts each adapter's own DNS back. Adapters are found again by guid, since an index can change between the apply
/// and the restore. Reset first (back to DHCP for both families), then re-apply whatever was static, so an adapter
/// with static IPv6 only does not keep our servers on IPv4. An adapter that is gone is skipped, not an error.
pub fn restore_script(adapters: &[AdapterDns]) -> String {
    let mut script = String::from("$ErrorActionPreference='Continue'\n");
    for a in adapters {
        script.push_str(&format!(
            "$i = (Get-NetAdapter | Where-Object {{ $_.InterfaceGuid -eq '{}' }}).ifIndex\nif ($i) {{\n  Set-DnsClientServerAddress -InterfaceIndex $i -ResetServerAddresses\n",
            a.guid
        ));
        let own = a.static_servers();
        if !own.is_empty() {
            let list = own
                .iter()
                .map(|ip| format!("'{ip}'"))
                .collect::<Vec<_>>()
                .join(",");
            script.push_str(&format!(
                "  Set-DnsClientServerAddress -InterfaceIndex $i -ServerAddresses ({list})\n"
            ));
        }
        script.push_str("}\n");
    }
    script.push_str("& \"$env:SystemRoot\\System32\\ipconfig.exe\" /flushdns | Out-Null\n");
    script
}

/// What the service remembers about the DNS it set: the servers, and whether the user keeps them on outside sessions.
/// Persisted next to the adapter backup, so it survives the app, a reboot and a service restart.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DnsState {
    pub servers: Vec<String>,
    pub keep: bool,
}

/// What the service does next. The rules live here, not in the service, so they are tested on any host.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DnsPlan {
    Apply(Vec<String>),
    Restore,
    Nothing,
}

impl DnsState {
    /// A session applied [servers]. Keeping stays as it was: a session never turns always-on off, or on.
    pub fn after_session_apply(prev: Option<&DnsState>, servers: &[String]) -> DnsState {
        DnsState {
            servers: servers.to_vec(),
            keep: prev.is_some_and(|p| p.keep),
        }
    }

    pub fn keeping(servers: &[String]) -> DnsState {
        DnsState {
            servers: servers.to_vec(),
            keep: true,
        }
    }
}

/// A session ended (`ClearDns`): put the adapters back, unless the user keeps this DNS on.
pub fn on_clear(state: Option<&DnsState>) -> DnsPlan {
    if state.is_some_and(|s| s.keep) {
        DnsPlan::Nothing
    } else {
        DnsPlan::Restore
    }
}

/// The user turned "always on" off: restore, but only if it was the thing holding the DNS.
pub fn on_release(state: Option<&DnsState>) -> DnsPlan {
    if state.is_some_and(|s| s.keep) {
        DnsPlan::Restore
    } else {
        DnsPlan::Nothing
    }
}

/// The service started (boot, upgrade, crash). A kept DNS is re-applied; anything else left behind is undone,
/// because a session that is still running is re-applied by the app within its next check.
pub fn on_start(state: Option<&DnsState>) -> DnsPlan {
    match state {
        Some(s) if s.keep => DnsPlan::Apply(s.servers.clone()),
        _ => DnsPlan::Restore,
    }
}

/// The service is stopping: leave a kept DNS in place (an upgrade must not drop the filter), undo the rest.
pub fn on_shutdown(state: Option<&DnsState>) -> DnsPlan {
    on_clear(state)
}

/// The status the DNS screen shows. `expected` is what the stored choice should be applying (empty: system DNS);
/// `applied` is what the service reports, `None` when it has applied nothing; `service_up` is whether it answered.
pub fn status(
    expected: &[String],
    always_on: bool,
    session_live: bool,
    service_up: bool,
    applied: Option<&DnsState>,
) -> &'static str {
    if expected.is_empty() {
        "off"
    } else if !(always_on || session_live) {
        "waiting"
    } else if !service_up {
        "unavailable"
    } else if applied.is_some_and(|a| a.servers == expected) {
        "active"
    } else {
        "notApplied"
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const G1: &str = "{11111111-2222-3333-4444-555555555555}";

    #[test]
    fn system_and_a_bad_custom_choice_touch_nothing() {
        assert!(servers_for(DnsProvider::System, "").is_empty());
        assert!(servers_for(DnsProvider::Custom, "").is_empty());
        assert!(servers_for(DnsProvider::Custom, "1.2.3").is_empty());
    }

    #[test]
    fn presets_match_android() {
        assert_eq!(
            servers_for(DnsProvider::AdguardFamily, ""),
            vec!["94.140.14.15", "94.140.15.16"]
        );
        assert_eq!(
            servers_for(DnsProvider::CloudflareFamily, ""),
            vec!["1.1.1.3", "1.0.0.3"]
        );
        assert_eq!(
            servers_for(DnsProvider::CleanbrowsingFamily, ""),
            vec!["185.228.168.168", "185.228.169.168"]
        );
        assert_eq!(
            servers_for(DnsProvider::Quad9, ""),
            vec!["9.9.9.9", "149.112.112.112"]
        );
    }

    #[test]
    fn ids_round_trip() {
        for p in DnsProvider::ALL {
            assert_eq!(DnsProvider::from_id(p.id()), Some(p));
        }
        assert_eq!(DnsProvider::from_id("nope"), None);
    }

    #[test]
    fn custom_lists_are_cleaned_and_bounded() {
        let ips = parse_servers("192.168.1.2, 1.1.1.1;1.1.1.1  2606:4700:4700::1111").unwrap();
        assert_eq!(ips.len(), 3, "duplicates collapse");
        assert!(
            parse_servers("1.1.1.1 1.0.0.1 8.8.8.8 8.8.4.4 9.9.9.9").is_err(),
            "more than four"
        );
        assert!(parse_servers("").is_err());
    }

    #[test]
    fn unusable_addresses_are_refused() {
        for bad in [
            "0.0.0.0",
            "255.255.255.255",
            "224.0.0.1",
            "::",
            "ff02::1",
            "fe80::1",
            "dns.example.com",
            "1.1.1.1/24",
            "1.1.1.1; calc",
        ] {
            assert!(parse_servers(bad).is_err(), "{bad} must be refused");
        }
        assert!(
            parse_servers("127.0.0.1").is_ok(),
            "a local resolver is fine"
        );
    }

    #[test]
    fn the_service_revalidates_what_arrives_on_the_pipe() {
        assert!(validate_servers(&["1.1.1.3".to_string(), "1.0.0.3".to_string()]).is_ok());
        assert!(validate_servers(&["1.1.1.3'; Remove-Item C:\\ -Recurse #".to_string()]).is_err());
        assert!(validate_servers(&[]).is_err());
    }

    #[test]
    fn adapter_listing_survives_powershells_json_quirks() {
        assert_eq!(parse_adapters("").unwrap(), vec![]);
        assert_eq!(parse_adapters("null").unwrap(), vec![]);
        assert_eq!(parse_adapters("[null]").unwrap(), vec![]);
        let one = format!(r#"[{{"index":12,"guid":"{G1}","v4":"","v6":""}}]"#);
        assert_eq!(parse_adapters(&one).unwrap()[0].index, 12);
        let bad = r#"[{"index":1,"guid":"{x'; calc}","v4":"","v6":""}]"#;
        assert!(
            parse_adapters(bad).is_err(),
            "a guid is checked before it goes into a script"
        );
    }

    #[test]
    fn apply_sets_every_adapter_and_flushes() {
        let adapters = vec![
            AdapterDns {
                index: 12,
                guid: G1.into(),
                v4: String::new(),
                v6: String::new(),
            },
            AdapterDns {
                index: 7,
                guid: G1.into(),
                v4: String::new(),
                v6: String::new(),
            },
        ];
        let ips = parse_servers("94.140.14.15,94.140.15.16").unwrap();
        let script = apply_script(&ips, &adapters);
        assert!(
            script.contains("-InterfaceIndex 12 -ServerAddresses ('94.140.14.15','94.140.15.16')")
        );
        assert!(script.contains("-InterfaceIndex 7 "));
        assert!(script.contains("ipconfig.exe\" /flushdns"));
    }

    #[test]
    fn restore_resets_to_dhcp_then_reapplies_only_what_was_static() {
        let dhcp = AdapterDns {
            index: 1,
            guid: G1.into(),
            v4: String::new(),
            v6: String::new(),
        };
        let fixed = AdapterDns {
            index: 2,
            guid: G1.into(),
            v4: "192.168.1.2,192.168.1.3".into(),
            v6: String::new(),
        };
        let dhcp_script = restore_script(&[dhcp]);
        assert!(dhcp_script.contains("-ResetServerAddresses"));
        assert!(
            !dhcp_script.contains("-ServerAddresses ("),
            "DHCP adapters get nothing re-applied"
        );
        let fixed_script = restore_script(&[fixed]);
        assert!(fixed_script.contains("-ResetServerAddresses"));
        assert!(fixed_script.contains("-ServerAddresses ('192.168.1.2','192.168.1.3')"));
        assert!(
            fixed_script.contains(G1),
            "found again by guid, not by index"
        );
    }

    #[test]
    fn restore_ignores_junk_in_a_static_list() {
        let a = AdapterDns {
            index: 1,
            guid: G1.into(),
            v4: "8.8.8.8 ; evil".into(),
            v6: String::new(),
        };
        let script = restore_script(&[a]);
        assert!(script.contains("('8.8.8.8')"));
        assert!(!script.contains("evil"));
    }

    fn st(servers: &[&str], keep: bool) -> DnsState {
        DnsState {
            servers: servers.iter().map(|s| s.to_string()).collect(),
            keep,
        }
    }

    #[test]
    fn a_session_never_changes_whether_the_dns_is_kept() {
        let v = vec!["9.9.9.9".to_string()];
        assert!(!DnsState::after_session_apply(None, &v).keep);
        assert!(!DnsState::after_session_apply(Some(&st(&["1.1.1.3"], false)), &v).keep);
        let kept = DnsState::after_session_apply(Some(&st(&["1.1.1.3"], true)), &v);
        assert!(kept.keep);
        assert_eq!(kept.servers, v, "a new pick replaces the servers");
        assert!(DnsState::keeping(&v).keep);
    }

    #[test]
    fn ending_a_session_restores_unless_the_dns_is_kept() {
        assert_eq!(on_clear(None), DnsPlan::Restore);
        assert_eq!(on_clear(Some(&st(&["9.9.9.9"], false))), DnsPlan::Restore);
        assert_eq!(on_clear(Some(&st(&["9.9.9.9"], true))), DnsPlan::Nothing);
    }

    #[test]
    fn turning_always_on_off_restores_only_when_it_was_on() {
        assert_eq!(on_release(None), DnsPlan::Nothing);
        assert_eq!(
            on_release(Some(&st(&["9.9.9.9"], false))),
            DnsPlan::Nothing,
            "a running session's DNS is not touched"
        );
        assert_eq!(on_release(Some(&st(&["9.9.9.9"], true))), DnsPlan::Restore);
    }

    #[test]
    fn start_and_shutdown_keep_a_kept_dns_and_undo_the_rest() {
        let kept = st(&["9.9.9.9"], true);
        let session = st(&["9.9.9.9"], false);
        assert_eq!(
            on_start(Some(&kept)),
            DnsPlan::Apply(vec!["9.9.9.9".to_string()])
        );
        assert_eq!(on_start(Some(&session)), DnsPlan::Restore);
        assert_eq!(on_start(None), DnsPlan::Restore);
        assert_eq!(on_shutdown(Some(&kept)), DnsPlan::Nothing);
        assert_eq!(on_shutdown(Some(&session)), DnsPlan::Restore);
    }

    #[test]
    fn the_status_reads_off_waiting_unavailable_not_applied_or_active() {
        let want = vec!["94.140.14.15".to_string(), "94.140.15.16".to_string()];
        let applied = DnsState {
            servers: want.clone(),
            keep: true,
        };
        assert_eq!(status(&[], true, true, true, None), "off");
        assert_eq!(status(&want, false, false, true, None), "waiting");
        assert_eq!(status(&want, true, false, false, None), "unavailable");
        assert_eq!(status(&want, true, false, true, None), "notApplied");
        assert_eq!(
            status(&want, true, false, true, Some(&st(&["8.8.8.8"], true))),
            "notApplied",
            "another server is applied"
        );
        assert_eq!(status(&want, true, false, true, Some(&applied)), "active");
        assert_eq!(
            status(&want, false, true, true, Some(&applied)),
            "active",
            "a live session counts too"
        );
    }
}
