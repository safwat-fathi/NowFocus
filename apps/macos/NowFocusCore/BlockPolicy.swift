import Foundation

public enum PolicyMode: String, Codable {
    case blocklist
    case allowlist
}

public struct DomainRule: Codable, Identifiable {
    public let id: String
    public let domain: String
    public let includeSubdomains: Bool
    public var enabled: Bool
    
    public init(id: String = UUID().uuidString.lowercased(),
                domain: String,
                includeSubdomains: Bool = true,
                enabled: Bool = true) {
        self.id = id
        self.domain = domain
        self.includeSubdomains = includeSubdomains
        self.enabled = enabled
    }
}

public struct ApplicationRule: Codable, Identifiable {
    public let id: String
    public let platform: String
    public let nativeIdentifier: String
    public let displayName: String
    public var enabled: Bool
    
    public init(id: String = UUID().uuidString.lowercased(),
                platform: String = "macos",
                nativeIdentifier: String,
                displayName: String,
                enabled: Bool = true) {
        self.id = id
        self.platform = platform
        self.nativeIdentifier = nativeIdentifier
        self.displayName = displayName
        self.enabled = enabled
    }
}

public struct BlockPolicy: Codable, Identifiable {
    public let id: String
    public var name: String
    public let mode: PolicyMode

    /// Where this policy originated — user action, extension API, or schedule.
    public let source: SessionSource
    /// The extension that created this policy, if `source == .extensionAPI`.
    public let createdByExtensionId: String?
    /// Display metadata for the creating extension (name, icon) for UI attribution.
    public let extensionMetadata: ExtensionMeta?
    
    public var domains: [DomainRule]
    public var applications: [ApplicationRule]
    public var categories: [String]
    /// The `FeedRules.enforceable` names switched on (wire `partial`). Names a Mac can't enforce stay in the
    /// synced JSON, not here.
    public var partial: [String]
    
    public let notificationPolicy: NotificationMode
    
    public let createdAt: Date
    public var updatedAt: Date
    public let revision: Int
    
    public init(id: String = UUID().uuidString.lowercased(),
                name: String,
                mode: PolicyMode = .blocklist,
                source: SessionSource = .user,
                createdByExtensionId: String? = nil,
                extensionMetadata: ExtensionMeta? = nil,
                domains: [DomainRule] = [],
                applications: [ApplicationRule] = [],
                categories: [String] = [],
                partial: [String] = [],
                notificationPolicy: NotificationMode = .normal,
                createdAt: Date = Date(),
                updatedAt: Date = Date(),
                revision: Int = 1) {
        self.id = id
        self.name = name
        self.mode = mode
        self.source = source
        self.createdByExtensionId = createdByExtensionId
        self.extensionMetadata = extensionMetadata
        self.domains = domains
        self.applications = applications
        self.categories = categories
        self.partial = partial
        self.notificationPolicy = notificationPolicy
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.revision = revision
    }

    private enum CodingKeys: String, CodingKey {
        case id, name, mode, source, createdByExtensionId, extensionMetadata, domains, applications, categories, partial
        case notificationPolicy, createdAt, updatedAt, revision
    }

    /// Hand-written only so that JSON from before `partial` existed (no such key) still decodes.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decode(String.self, forKey: .name)
        mode = try c.decode(PolicyMode.self, forKey: .mode)
        source = try c.decode(SessionSource.self, forKey: .source)
        createdByExtensionId = try c.decodeIfPresent(String.self, forKey: .createdByExtensionId)
        extensionMetadata = try c.decodeIfPresent(ExtensionMeta.self, forKey: .extensionMetadata)
        domains = try c.decode([DomainRule].self, forKey: .domains)
        applications = try c.decode([ApplicationRule].self, forKey: .applications)
        categories = try c.decode([String].self, forKey: .categories)
        partial = try c.decodeIfPresent([String].self, forKey: .partial) ?? []
        notificationPolicy = try c.decode(NotificationMode.self, forKey: .notificationPolicy)
        createdAt = try c.decode(Date.self, forKey: .createdAt)
        updatedAt = try c.decode(Date.self, forKey: .updatedAt)
        revision = try c.decode(Int.self, forKey: .revision)
    }
}

extension BlockPolicy {
    /// The starter profile a fresh install gets. One definition, so the first-sign-in merge can tell an untouched
    /// starter from a profile the user made (and drop it when the account already has its own).
    public static let seedName = "Deep Work"
    public static let seedDomains = ["youtube.com", "twitter.com", "x.com", "reddit.com", "instagram.com", "tiktok.com", "facebook.com"]

    public static func makeSeed() -> BlockPolicy {
        BlockPolicy(name: seedName, domains: seedDomains.map { DomainRule(domain: $0, includeSubdomains: true) })
    }

    public var isUntouchedSeed: Bool {
        name == Self.seedName && applications.isEmpty && mode == .blocklist
            && domains.allSatisfy { $0.enabled && $0.includeSubdomains } && Set(domains.map(\.domain)) == Set(Self.seedDomains)
    }
}
