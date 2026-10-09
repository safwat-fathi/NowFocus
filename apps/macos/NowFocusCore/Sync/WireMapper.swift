import Foundation

/// The Mac's `BlockPolicy` <-> the server's policy `data` (services/api/WIRE_FORMAT.md, section 4).
///
/// The one rule that matters: policies are last-write-wins as a whole, so an edit is MERGED into the last server
/// JSON, never rebuilt from the Mac's model. This Mac owns `name`, `mode`, its domain rules (including `enabled` and
/// `includeSubdomains`), the app rules with `platform: "macos"` and the three `partial` names it can enforce
/// (`FeedRules.enforceable`). Everything else (other `partial` names, `categories`, `notificationPolicy`,
/// `feedRules`, other platforms' app rules, unknown fields) is carried over untouched.
enum PolicyWire {
    static let platform = "macos"

    /// macOS enforces blocklists only: applying an allowlist's domains as blocks would do the opposite of what the
    /// user set. An unknown future mode is treated the same way (kept, never enforced).
    static func supported(_ raw: JSONObject) -> Bool {
        let mode = JSONKit.string(raw, "mode")
        return mode == nil || mode == "blocklist"
    }

    static func toLocal(_ raw: JSONObject, createdAt: Date = Date()) -> BlockPolicy {
        let domains: [DomainRule] = JSONKit.objects(raw, "domainRules").compactMap { r in
            guard let d = JSONKit.string(r, "domain").flatMap(DomainValidation.normalize) else { return nil }   // never trust a pulled domain
            return DomainRule(id: JSONKit.string(r, "id") ?? UUID().uuidString.lowercased(), domain: d,
                              includeSubdomains: JSONKit.bool(r, "includeSubdomains") ?? true, enabled: JSONKit.bool(r, "enabled") ?? true)
        }
        let apps: [ApplicationRule] = JSONKit.objects(raw, "applicationRules").compactMap { r in
            guard JSONKit.string(r, "platform") == platform, let id = JSONKit.string(r, "nativeIdentifier"), !id.isEmpty else { return nil }
            return ApplicationRule(id: JSONKit.string(r, "id") ?? UUID().uuidString.lowercased(), nativeIdentifier: id,
                                   displayName: JSONKit.string(r, "displayName") ?? id, enabled: JSONKit.bool(r, "enabled") ?? true)
        }
        return BlockPolicy(
            id: (JSONKit.string(raw, "id") ?? "").lowercased(),
            name: JSONKit.string(raw, "name") ?? "",
            mode: .blocklist,
            domains: domains,
            applications: apps,
            categories: JSONKit.array(raw, "categories").compactMap { $0 as? String },
            partial: FeedRules.enforceable.filter { owned in JSONKit.array(raw, "partial").contains { ($0 as? String) == owned } },
            notificationPolicy: JSONKit.string(raw, "notificationPolicy").flatMap(NotificationMode.init(rawValue:)) ?? .normal,
            createdAt: createdAt
        )
    }

    /// `raw` is the last server record (nil for a brand-new profile). Never mutates it.
    static func merge(_ local: BlockPolicy, into raw: JSONObject?) -> JSONObject {
        var out = raw ?? [:]
        out["id"] = local.id.lowercased()
        out["name"] = local.name
        out["mode"] = local.mode.rawValue
        out["domainRules"] = mergeDomains(local.domains, existing: JSONKit.objects(out, "domainRules"))
        out["applicationRules"] = mergeApps(local.applications, existing: JSONKit.objects(out, "applicationRules"))
        mergePartial(local.partial, into: &out)
        return out
    }

    /// Switches this Mac's names on or off and leaves every other entry (Android's `X_FOR_YOU`, a newer platform's
    /// name, a non-string) exactly as it was.
    private static func mergePartial(_ mine: [String], into out: inout JSONObject) {
        let owned = Set(FeedRules.enforceable)
        var next: [Any] = JSONKit.array(out, "partial").filter { entry in !((entry as? String).map(owned.contains) ?? false) }
        for name in FeedRules.enforceable where mine.contains(name) { next.append(name) }
        let had = out["partial"] != nil && !(out["partial"] is NSNull)
        if next.isEmpty && !had { return }
        out["partial"] = next
    }

    private static func mergeDomains(_ domains: [DomainRule], existing: [JSONObject]) -> [JSONObject] {
        var wanted: [String: DomainRule] = [:]
        var order: [String] = []
        for d in domains { let k = domainKey(d.domain); if wanted[k] == nil { wanted[k] = d; order.append(k) } }
        var seen = Set<String>()
        var rules: [JSONObject] = []
        for var r in existing {
            guard let raw = JSONKit.string(r, "domain") else { rules.append(r); continue }      // not a rule we can read: keep it verbatim
            let key = domainKey(raw)
            // Gone locally = the user removed it (this Mac can express a disabled rule, so "disabled" is not "gone").
            guard let mine = wanted[key], !seen.contains(key) else { continue }
            if JSONKit.bool(r, "enabled") != mine.enabled { r["enabled"] = mine.enabled }
            if JSONKit.bool(r, "includeSubdomains") != mine.includeSubdomains { r["includeSubdomains"] = mine.includeSubdomains }
            rules.append(r); seen.insert(key)
        }
        for key in order where !seen.contains(key) {
            let d = wanted[key]!
            rules.append(["id": d.id.lowercased(), "domain": key, "includeSubdomains": d.includeSubdomains, "enabled": d.enabled])
        }
        return rules
    }

    private static func mergeApps(_ apps: [ApplicationRule], existing: [JSONObject]) -> [JSONObject] {
        var wanted: [String: ApplicationRule] = [:]
        var order: [String] = []
        for a in apps where wanted[a.nativeIdentifier] == nil { wanted[a.nativeIdentifier] = a; order.append(a.nativeIdentifier) }
        var seen = Set<String>()
        var rules: [JSONObject] = []
        for var r in existing {
            guard JSONKit.string(r, "platform") == platform else { rules.append(r); continue }   // another platform's rule: never ours
            guard let key = JSONKit.string(r, "nativeIdentifier") else { rules.append(r); continue }
            guard let mine = wanted[key], !seen.contains(key) else { continue }
            if JSONKit.string(r, "displayName") != mine.displayName { r["displayName"] = mine.displayName }
            if JSONKit.bool(r, "enabled") != mine.enabled { r["enabled"] = mine.enabled }
            rules.append(r); seen.insert(key)
        }
        for key in order where !seen.contains(key) {
            let a = wanted[key]!
            rules.append(["id": a.id.lowercased(), "platform": platform, "nativeIdentifier": key, "displayName": a.displayName, "enabled": a.enabled])
        }
        return rules
    }

    /// Same meaning, ignoring order, rule ids and the spelling of domains.
    static func same(_ a: BlockPolicy, _ b: BlockPolicy) -> Bool { canonical(a) == canonical(b) }

    /// A stable string for the content of `p`.
    static func canonical(_ p: BlockPolicy) -> String {
        let domains = Set(p.domains.map { "\(domainKey($0.domain))|\($0.includeSubdomains)|\($0.enabled)" }).sorted().joined(separator: ",")
        let apps = Set(p.applications.map { "\($0.nativeIdentifier)|\($0.displayName)|\($0.enabled)" }).sorted().joined(separator: ",")
        let partial = Set(p.partial).sorted().joined(separator: ",")
        return [p.id.lowercased(), p.name, p.mode.rawValue, domains, apps, partial].joined(separator: "\u{1}")
    }

    /// The key two spellings of one domain share; unparseable input falls back to trimmed lowercase.
    static func domainKey(_ d: String) -> String {
        DomainValidation.normalize(d) ?? d.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    /// Would swapping `old` for `new` stop blocking something `old` blocks? Compared by domain and app identifier,
    /// not rule id: ids don't survive a round trip through the server.
    static func weakens(old: BlockPolicy, new: BlockPolicy) -> Bool {
        let newDomains = Dictionary(new.domains.filter(\.enabled).map { (domainKey($0.domain), $0.includeSubdomains) }, uniquingKeysWith: { $0 || $1 })
        for d in old.domains where d.enabled {
            guard let incl = newDomains[domainKey(d.domain)] else { return true }
            if d.includeSubdomains && !incl { return true }
        }
        let newApps = Set(new.applications.filter(\.enabled).map(\.nativeIdentifier))
        if old.applications.filter(\.enabled).contains(where: { !newApps.contains($0.nativeIdentifier) }) { return true }
        return !Set(old.partial).isSubset(of: Set(new.partial))
    }
}

/// Bedtime is a singleton (`bedtime_settings`, id `default`). `quietNotifications` is Android-only: it is never
/// written here and survives in the merged JSON.
enum BedtimeWire {
    static let defaults = BedtimeSettings()

    static func toLocal(_ raw: JSONObject) -> BedtimeSettings {
        var b = BedtimeSettings()
        b.windDownMinute = minute(raw, "windDownMinute") ?? defaults.windDownMinute
        b.sleepMinute = minute(raw, "sleepMinute") ?? defaults.sleepMinute
        b.wakeMinute = minute(raw, "wakeMinute") ?? defaults.wakeMinute
        b.enabled = JSONKit.bool(raw, "enabled") ?? defaults.enabled
        b.lockAtSleep = JSONKit.bool(raw, "lockAtSleep") ?? defaults.lockAtSleep
        b.policyId = JSONKit.string(raw, "policyId")?.lowercased()
        return b
    }

    /// Minutes are device-local and server-validated to 0...1439; anything else is ignored rather than trusted.
    private static func minute(_ raw: JSONObject, _ key: String) -> Int? {
        JSONKit.int(raw, key).flatMap { (0...1439).contains($0) ? $0 : nil }
    }

    static func merge(_ local: BedtimeSettings, into raw: JSONObject?) -> JSONObject {
        var out = raw ?? [:]
        out["enabled"] = local.enabled
        out["windDownMinute"] = local.windDownMinute
        out["sleepMinute"] = local.sleepMinute
        out["wakeMinute"] = local.wakeMinute
        out["lockAtSleep"] = local.lockAtSleep
        out["policyId"] = local.policyId?.lowercased() ?? NSNull()
        return out
    }

    static func canonical(_ b: BedtimeSettings) -> String {
        "\(b.enabled)|\(b.windDownMinute)|\(b.sleepMinute)|\(b.wakeMinute)|\(b.lockAtSleep)|\(b.policyId?.lowercased() ?? "")"
    }

    static func same(_ a: BedtimeSettings, _ b: BedtimeSettings) -> Bool { canonical(a) == canonical(b) }
}
