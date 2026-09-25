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
    
    public init(id: String = UUID().uuidString,
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
    
    public init(id: String = UUID().uuidString,
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
    
    public let notificationPolicy: NotificationMode
    
    public let createdAt: Date
    public var updatedAt: Date
    public let revision: Int
    
    public init(id: String = UUID().uuidString,
                name: String,
                mode: PolicyMode = .blocklist,
                source: SessionSource = .user,
                createdByExtensionId: String? = nil,
                extensionMetadata: ExtensionMeta? = nil,
                domains: [DomainRule] = [],
                applications: [ApplicationRule] = [],
                categories: [String] = [],
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
        self.notificationPolicy = notificationPolicy
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.revision = revision
    }
}
