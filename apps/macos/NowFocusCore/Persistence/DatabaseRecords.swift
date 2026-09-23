import GRDB
import Foundation

// MARK: - FocusSession GRDB Conformance
extension FocusSession: FetchableRecord, PersistableRecord {
    public static let databaseTableName = "focusSession"
    
    // We get mapping for free via Codable for standard types,
    // but we might need explicit CodingKeys if the enum values are stored as string.
    // GRDB handles Codable structs nicely.
}

// MARK: - BlockPolicy GRDB Conformance
// Because BlockPolicy contains nested arrays (domains, applications), we need
// to map them to the blobs we defined in the schema.
public struct BlockPolicyRecord: Codable, FetchableRecord, PersistableRecord {
    public static let databaseTableName = "blockPolicy"
    
    public let id: String
    public let name: String
    public let mode: PolicyMode
    
    public var domainsData: Data
    public var applicationsData: Data
    public var categoriesData: Data
    
    public let notificationPolicy: NotificationMode
    
    public let createdAt: Date
    public var updatedAt: Date
    public let revision: Int
    
    public init(policy: BlockPolicy) throws {
        self.id = policy.id
        self.name = policy.name
        self.mode = policy.mode
        self.domainsData = try JSONEncoder().encode(policy.domains)
        self.applicationsData = try JSONEncoder().encode(policy.applications)
        self.categoriesData = try JSONEncoder().encode(policy.categories)
        self.notificationPolicy = policy.notificationPolicy
        self.createdAt = policy.createdAt
        self.updatedAt = policy.updatedAt
        self.revision = policy.revision
    }
    
    public func toPolicy() throws -> BlockPolicy {
        let domains = try JSONDecoder().decode([DomainRule].self, from: domainsData)
        let apps = try JSONDecoder().decode([ApplicationRule].self, from: applicationsData)
        let cats = try JSONDecoder().decode([String].self, from: categoriesData)
        
        return BlockPolicy(
            id: id,
            name: name,
            mode: mode,
            domains: domains,
            applications: apps,
            categories: cats,
            notificationPolicy: notificationPolicy,
            createdAt: createdAt,
            updatedAt: updatedAt,
            revision: revision
        )
    }
}

// MARK: - SessionEvent
public struct SessionEvent: Codable, Identifiable {
    public let id: String
    public let sessionId: String
    public let type: String
    public let occurredAt: Date
    public let metadataJson: String?
    
    public init(id: String = UUID().uuidString,
                sessionId: String,
                type: String,
                occurredAt: Date = Date(),
                metadataJson: String? = nil) {
        self.id = id
        self.sessionId = sessionId
        self.type = type
        self.occurredAt = occurredAt
        self.metadataJson = metadataJson
    }
}

extension SessionEvent: FetchableRecord, PersistableRecord {
    public static let databaseTableName = "sessionEvent"
}
