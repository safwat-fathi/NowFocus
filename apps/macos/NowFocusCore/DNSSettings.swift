import Foundation

/// "DNS during a session": the resolver the Mac is pointed at while a focus session runs.
///
/// Android forwards lookups itself (apps/android DnsUpstream.kt). The Mac has no tunnel, only a hosts file, so the
/// privileged daemon changes the DNS servers of the network services for the length of the session and puts them
/// back afterwards. The presets are the same plain-DNS addresses Android and Windows use, so a family filter (ads,
/// adult content) keeps working next to NowFocus's own blocklist.
///
/// Foundation-only on purpose: the choice, the validation (the daemon re-validates, since any local process can
/// reach its XPC service) and the reading of `networksetup`'s output are checked by scripts/core-checks.sh.
///
/// ponytail: presets are IPv4 only (the IPv6 twins could not be verified from a machine without IPv6). A service that
/// also has IPv6 DNS from the router can still answer from there; the hosts file blocks regardless.
public enum DNSProvider: String, CaseIterable, Codable {
    /// Leave the network services alone (the default).
    case system
    case cloudflareFamily = "cloudflare_family"
    case adguardFamily = "adguard_family"
    case cleanbrowsingFamily = "cleanbrowsing_family"
    case quad9
    /// Addresses the user typed.
    case custom

    fileprivate var preset: [String] {
        switch self {
        case .cloudflareFamily: return ["1.1.1.3", "1.0.0.3"]
        case .adguardFamily: return ["94.140.14.15", "94.140.15.16"]
        case .cleanbrowsingFamily: return ["185.228.168.168", "185.228.169.168"]
        case .quad9: return ["9.9.9.9", "149.112.112.112"]
        case .system, .custom: return []
        }
    }
}

public struct DNSChoice: Codable, Equatable {
    public var provider: DNSProvider
    /// The addresses typed for `.custom`, kept even while another provider is picked.
    public var custom: String
    /// Keep this DNS on outside focus sessions too (default off).
    public var alwaysOn: Bool

    public init(provider: DNSProvider = .system, custom: String = "", alwaysOn: Bool = false) {
        self.provider = provider
        self.custom = custom
        self.alwaysOn = alwaysOn
    }

    // A choice saved before "always on" existed has no such key; it decodes as off.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        provider = try c.decode(DNSProvider.self, forKey: .provider)
        custom = try c.decode(String.self, forKey: .custom)
        alwaysOn = try c.decodeIfPresent(Bool.self, forKey: .alwaysOn) ?? false
    }

    /// What to apply. Empty means "do not touch the network services": the System choice, and a custom list that is
    /// not valid (a half-typed list must never reach a network service).
    public var servers: [String] {
        switch provider {
        case .system: return []
        case .custom: return DNSResolvers.parse(custom) ?? []
        default: return provider.preset
        }
    }

    private static let key = "dnsDuringSession"

    /// Local to this Mac (never synced), like `RuleGroup`.
    public static func load(_ defaults: UserDefaults = .standard) -> DNSChoice {
        defaults.data(forKey: key).flatMap { try? JSONDecoder().decode(DNSChoice.self, from: $0) } ?? DNSChoice()
    }

    public func save(_ defaults: UserDefaults = .standard) {
        if let data = try? JSONEncoder().encode(self) { defaults.set(data, forKey: Self.key) }
    }
}

public enum DNSResolvers {
    public static let maxServers = 4

    /// 1 to `maxServers` addresses separated by commas, semicolons or whitespace; nil if anything is not usable.
    public static func parse(_ raw: String) -> [String]? {
        var out: [String] = []
        for token in raw.split(whereSeparator: { $0 == "," || $0 == ";" || $0.isWhitespace }) {
            guard let ip = canonical(String(token)) else { return nil }
            if !out.contains(ip) { out.append(ip) }
        }
        return (1...maxServers).contains(out.count) ? out : nil
    }

    /// What the daemon accepts over XPC: the same rules, for already-split strings.
    public static func validate(_ servers: [String]) -> [String]? {
        parse(servers.joined(separator: ","))
    }

    /// The address in its canonical text form, or nil. Loopback and private ranges stay allowed (a Pi-hole or a local
    /// resolver is a normal choice); unspecified, multicast, broadcast and unscoped link-local IPv6 are not.
    static func canonical(_ text: String) -> String? {
        var v4 = in_addr()
        if inet_pton(AF_INET, text, &v4) == 1 {
            let host = UInt32(bigEndian: v4.s_addr)
            if host == 0 || host == 0xFFFF_FFFF || (host >> 28) == 0xE { return nil }
            return text
        }
        var v6 = in6_addr()
        if inet_pton(AF_INET6, text, &v6) == 1 {
            let bytes = withUnsafeBytes(of: &v6) { Array($0) }
            if bytes.allSatisfy({ $0 == 0 }) || bytes[0] == 0xFF { return nil }
            if bytes[0] == 0xFE && (bytes[1] & 0xC0) == 0x80 { return nil }
            return text.lowercased()
        }
        return nil
    }
}

/// One network service's own DNS, as `networksetup` reports it. Empty `servers` means "automatic" (from DHCP).
public struct ServiceDNS: Codable, Equatable {
    public let service: String
    public let servers: [String]

    public init(service: String, servers: [String]) {
        self.service = service
        self.servers = servers
    }
}

/// Reads what `/usr/sbin/networksetup` prints. Its text is not a stable format, so anything unexpected is treated as
/// "leave that service alone" rather than guessed at.
public enum NetworkSetupOutput {
    /// `-listallnetworkservices`: a header line, then one service per line; a leading `*` marks a disabled one.
    public static func services(_ output: String) -> [String] {
        output.split(separator: "\n").map(String.init)
            .filter { !$0.hasPrefix("An asterisk") && !$0.hasPrefix("*") && !$0.trimmingCharacters(in: .whitespaces).isEmpty }
    }

    /// `-getdnsservers <service>`: one address per line, or "There aren't any DNS Servers set on X." for automatic.
    /// nil when any line is not an address (an encrypted-DNS profile reports a URL here): such a service is managed
    /// by something else and must not be overwritten, or "restoring" it would write that URL back as a server.
    public static func dnsServers(_ output: String) -> [String]? {
        let lines = output.split(separator: "\n").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        if lines.isEmpty || (lines.count == 1 && lines[0].hasPrefix("There aren't any DNS Servers set on")) { return [] }
        var out: [String] = []
        for line in lines {
            guard let ip = DNSResolvers.canonical(line) else { return nil }
            out.append(ip)
        }
        return out
    }
}

/// What the daemon remembers about the DNS it set: the servers, and whether the user keeps them on outside sessions.
/// Persisted beside the service backup, so it survives the app, a reboot and a daemon restart.
public struct DNSState: Codable, Equatable {
    public let servers: [String]
    public let keep: Bool

    public init(servers: [String], keep: Bool) {
        self.servers = servers
        self.keep = keep
    }
}

/// What the daemon does next. The rules live here, not in the daemon, so they are checked by scripts/core-checks.sh
/// (and mirror core/src/dns_resolvers.rs on Windows).
public enum DNSPlan: Equatable {
    case apply([String])
    case restore
    case nothing
}

public enum DNSRules {
    /// A session applied `servers`. Keeping stays as it was: a session never turns always-on off, or on.
    public static func afterSessionApply(_ prev: DNSState?, _ servers: [String]) -> DNSState {
        DNSState(servers: servers, keep: prev?.keep ?? false)
    }

    /// A session ended (`clearDNS`): put the services back, unless the user keeps this DNS on.
    public static func onClear(_ state: DNSState?) -> DNSPlan { state?.keep == true ? .nothing : .restore }

    /// The user turned "always on" off: restore, but only if it was the thing holding the DNS.
    public static func onRelease(_ state: DNSState?) -> DNSPlan { state?.keep == true ? .restore : .nothing }

    /// The daemon started (boot, crash). A kept DNS is re-applied; anything else left behind is undone, because a
    /// session that is still running is re-applied by the app when it relaunches.
    public static func onStart(_ state: DNSState?) -> DNSPlan {
        if let state, state.keep { return .apply(state.servers) }
        return .restore
    }

    /// The status the DNS screen shows: "off", "waiting", "active", "notApplied" or "unavailable". `expected` is what
    /// the stored choice should apply (empty: system DNS); `applied` is what the daemon reports; `serviceUp` whether it answered.
    public static func status(expected: [String], alwaysOn: Bool, sessionLive: Bool, serviceUp: Bool, applied: DNSState?) -> String {
        if expected.isEmpty { return "off" }
        if !(alwaysOn || sessionLive) { return "waiting" }
        if !serviceUp { return "unavailable" }
        return applied?.servers == expected ? "active" : "notApplied"
    }
}
