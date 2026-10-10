import Foundation

/// A saved set of sites and apps that can be added into any profile. Local to this Mac (never synced: apps are
/// platform-specific and the server only knows profiles), so it lives in UserDefaults, not the profile database.
public struct RuleGroup: Codable, Identifiable, Equatable {
    public struct App: Codable, Equatable {
        public let nativeIdentifier: String
        public let displayName: String
        public init(nativeIdentifier: String, displayName: String) {
            self.nativeIdentifier = nativeIdentifier
            self.displayName = displayName
        }
    }

    public let id: String
    public var name: String
    public var domains: [String]
    public var applications: [App]

    public init(id: String = UUID().uuidString.lowercased(), name: String, domains: [String], applications: [App]) {
        self.id = id
        self.name = name
        self.domains = domains
        self.applications = applications
    }

    /// Snapshot of what a profile lists now.
    public init(from policy: BlockPolicy) {
        self.init(
            name: policy.name,
            domains: policy.domains.map(\.domain),
            applications: policy.applications.map { App(nativeIdentifier: $0.nativeIdentifier, displayName: $0.displayName) }
        )
    }

    private static let key = "ruleGroups"

    public static func load(_ defaults: UserDefaults = .standard) -> [RuleGroup] {
        defaults.data(forKey: key).flatMap { try? JSONDecoder().decode([RuleGroup].self, from: $0) } ?? []
    }

    public static func save(_ groups: [RuleGroup], _ defaults: UserDefaults = .standard) {
        defaults.set(try? JSONEncoder().encode(groups), forKey: key)
    }
}

public extension BlockPolicy {
    /// Adds the group's sites and apps, skipping what is already listed. An allowlist has no site list.
    mutating func add(_ group: RuleGroup) {
        if mode == .blocklist {
            for d in group.domains where !domains.contains(where: { $0.domain == d }) {
                domains.append(DomainRule(domain: d))
            }
        }
        for a in group.applications where !applications.contains(where: { $0.nativeIdentifier == a.nativeIdentifier }) {
            applications.append(ApplicationRule(nativeIdentifier: a.nativeIdentifier, displayName: a.displayName))
        }
    }
}
