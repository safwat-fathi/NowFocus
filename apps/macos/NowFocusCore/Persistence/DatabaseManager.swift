import Foundation
import GRDB

public class DatabaseManager {
    public static let shared = DatabaseManager()
    
    public var dbQueue: DatabaseQueue!
    
    private init() {
        do {
            let appSupportURL = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            let databaseURL = appSupportURL.appendingPathComponent("NowFocus.sqlite")
            
            var configuration = Configuration()
            #if DEBUG
            configuration.prepareDatabase { db in
                db.trace { print($0) }
            }
            #endif
            
            dbQueue = try DatabaseQueue(path: databaseURL.path, configuration: configuration)
            try migrator.migrate(dbQueue)
            
        } catch {
            fatalError("Failed to initialize database: \(error)")
        }
    }
    
    private var migrator: DatabaseMigrator {
        var migrator = DatabaseMigrator()
        
        migrator.registerMigration("v1") { db in
            try db.create(table: "focusSession") { t in
                t.column("id", .text).primaryKey()
                t.column("policyId", .text).notNull()
                t.column("sessionType", .text).notNull()
                t.column("startAt", .datetime).notNull()
                t.column("endAt", .datetime).notNull()
                t.column("status", .text).notNull()
                t.column("enforcementMode", .text).notNull()
                t.column("notificationMode", .text).notNull()
                t.column("createdAt", .datetime).notNull()
                t.column("completedAt", .datetime)
                t.column("cancelledAt", .datetime)
                t.column("deviceId", .text).notNull()
                t.column("revision", .integer).notNull()
            }
            
            try db.create(table: "blockPolicy") { t in
                t.column("id", .text).primaryKey()
                t.column("name", .text).notNull()
                t.column("mode", .text).notNull()
                t.column("domainsData", .blob).notNull() // JSON serialized
                t.column("applicationsData", .blob).notNull() // JSON serialized
                t.column("categoriesData", .blob).notNull() // JSON serialized
                t.column("notificationPolicy", .text).notNull()
                t.column("createdAt", .datetime).notNull()
                t.column("updatedAt", .datetime).notNull()
                t.column("revision", .integer).notNull()
            }
            
            try db.create(table: "sessionEvent") { t in
                t.column("id", .text).primaryKey()
                t.column("sessionId", .text).notNull()
                t.column("type", .text).notNull()
                t.column("occurredAt", .datetime).notNull()
                t.column("metadataJson", .text)
            }
        }
        
        return migrator
    }
    
    // MARK: - CRUD Operations
    
    public func saveSession(_ session: FocusSession) throws {
        try dbQueue.write { db in
            try session.save(db)
        }
    }
    
    public func fetchActiveSession() throws -> FocusSession? {
        return try dbQueue.read { db in
            try FocusSession
                .filter(Column("status") == FocusSessionStatus.active.rawValue || Column("status") == FocusSessionStatus.scheduled.rawValue)
                .order(Column("createdAt").desc)
                .fetchOne(db)
        }
    }
    
    public func savePolicy(_ policy: BlockPolicy) throws {
        let record = try BlockPolicyRecord(policy: policy)
        try dbQueue.write { db in
            try record.save(db)
        }
    }
    
    public func fetchPolicy(id: String) throws -> BlockPolicy? {
        return try dbQueue.read { db in
            guard let record = try BlockPolicyRecord.fetchOne(db, key: id) else {
                return nil
            }
            return try record.toPolicy()
        }
    }
    
    public func logEvent(_ event: SessionEvent) throws {
        try dbQueue.write { db in
            try event.save(db)
        }
    }
    
    public func fetchAllPolicies() throws -> [BlockPolicy] {
        return try dbQueue.read { db in
            let records = try BlockPolicyRecord.fetchAll(db)
            return try records.map { try $0.toPolicy() }
        }
    }
    
    public func deletePolicy(id: String) throws {
        try dbQueue.write { db in
            _ = try BlockPolicyRecord.deleteOne(db, key: id)
        }
    }
    
    public func seedDefaultPolicyIfNeeded() {
        do {
            let existing = try fetchAllPolicies()
            guard existing.isEmpty else { return }
            
            let defaultPolicy = BlockPolicy(
                name: "Deep Work",
                domains: [
                    DomainRule(domain: "youtube.com", includeSubdomains: true),
                    DomainRule(domain: "twitter.com", includeSubdomains: true),
                    DomainRule(domain: "x.com", includeSubdomains: true),
                    DomainRule(domain: "reddit.com", includeSubdomains: true),
                    DomainRule(domain: "instagram.com", includeSubdomains: true),
                    DomainRule(domain: "tiktok.com", includeSubdomains: true),
                    DomainRule(domain: "facebook.com", includeSubdomains: true)
                ]
            )
            try savePolicy(defaultPolicy)
            print("Seeded default 'Deep Work' policy.")
        } catch {
            print("Failed to seed default policy: \(error)")
        }
    }
}
