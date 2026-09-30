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

        migrator.registerMigration("v2") { db in
            try db.alter(table: "focusSession") { t in
                t.add(column: "source", .text).notNull().defaults(to: SessionSource.user.rawValue)
                t.add(column: "createdByExtensionId", .text)
                t.add(column: "extensionMetadata", .blob)
                t.add(column: "pausedAt", .datetime)
            }
        }

        migrator.registerMigration("v3") { db in
            try db.create(table: "userGoal") { t in
                t.column("id", .text).primaryKey()
                t.column("text", .text).notNull()
                t.column("priority", .text).notNull()
                t.column("createdAt", .datetime).notNull()
                t.column("updatedAt", .datetime).notNull()
            }

            try db.create(table: "userConnection") { t in
                t.column("id", .text).primaryKey()
                t.column("name", .text).notNull()
                t.column("phoneNumber", .text)
                t.column("createdAt", .datetime).notNull()
                t.column("updatedAt", .datetime).notNull()
            }
        }

        // Null = "can't remember", the oldest possible answer for the reach-out rotation.
        migrator.registerMigration("v4") { db in
            try db.alter(table: "userConnection") { t in
                t.add(column: "lastTalkedAt", .datetime)
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

    /// Sessions that *started* in `[from, to)` — Stats aggregates over this.
    /// `sessionType` narrows it (Stats wants `.focus` only — a nightly
    /// `.bedtime_winddown` session would otherwise dominate focused-time and
    /// keep the streak alive every day regardless of real focus activity).
    public func fetchSessions(from: Date, to: Date, sessionType: SessionType? = nil) throws -> [FocusSession] {
        try dbQueue.read { db in
            var request = FocusSession.filter(Column("startAt") >= from && Column("startAt") < to)
            if let sessionType {
                request = request.filter(Column("sessionType") == sessionType.rawValue)
            }
            return try request.order(Column("startAt")).fetchAll(db)
        }
    }


    /// Logged events (e.g. "app_blocked") in `[from, to)`, optionally filtered by type.
    public func fetchEvents(from: Date, to: Date, type: String? = nil) throws -> [SessionEvent] {
        try dbQueue.read { db in
            var request = SessionEvent.filter(Column("occurredAt") >= from && Column("occurredAt") < to)
            if let type {
                request = request.filter(Column("type") == type)
            }
            return try request.fetchAll(db)
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
            // Cancel any active/scheduled session bound to this policy in the same
            // transaction, so deleting a profile can never leave an orphaned session
            // that `fetchActiveSession()` keeps returning forever.
            let orphaned = try FocusSession
                .filter(Column("policyId") == id)
                .filter(Column("status") == FocusSessionStatus.active.rawValue
                     || Column("status") == FocusSessionStatus.scheduled.rawValue)
                .fetchAll(db)
            for var session in orphaned {
                session.status = .cancelled
                session.cancelledAt = Date()
                try session.save(db)
            }
            _ = try BlockPolicyRecord.deleteOne(db, key: id)
        }
    }
    
    // MARK: - Goals

    public func saveGoal(_ goal: UserGoal) throws {
        try dbQueue.write { db in try goal.save(db) }
    }

    public func fetchAllGoals() throws -> [UserGoal] {
        try dbQueue.read { db in
            try UserGoal.order(Column("createdAt")).fetchAll(db)
        }
    }

    public func deleteGoal(id: String) throws {
        try dbQueue.write { db in _ = try UserGoal.deleteOne(db, key: id) }
    }

    /// A random high-priority goal, falling back to any goal (see `Goals.pick`).
    /// Used in unlock and block overlays for motivation.
    public func fetchRandomGoal() throws -> UserGoal? {
        Goals.pick(try fetchAllGoals())
    }

    // MARK: - Connections

    public func saveConnection(_ connection: UserConnection) throws {
        try dbQueue.write { db in try connection.save(db) }
    }

    public func fetchAllConnections() throws -> [UserConnection] {
        try dbQueue.read { db in
            try UserConnection.order(Column("createdAt")).fetchAll(db)
        }
    }

    public func deleteConnection(id: String) throws {
        try dbQueue.write { db in _ = try UserConnection.deleteOne(db, key: id) }
    }

    /// Whoever you talked to longest ago (see `PeopleRotation`); nil when nobody has a number.
    public func fetchNextConnection() throws -> UserConnection? {
        PeopleRotation.next(try fetchAllConnections())
    }

    /// Stamps "talked just now". A Call/Text tap counts even if it never becomes a call.
    public func markTalked(connectionId: String, at date: Date = Date()) throws {
        try dbQueue.write { db in
            guard var connection = try UserConnection.fetchOne(db, key: connectionId) else { return }
            connection.lastTalkedAt = date
            connection.updatedAt = date
            try connection.update(db)
        }
    }

    /// Sets the "last talked" chip's date directly (nil = can't remember).
    public func setLastTalked(connectionId: String, to date: Date?) throws {
        try dbQueue.write { db in
            guard var connection = try UserConnection.fetchOne(db, key: connectionId) else { return }
            connection.lastTalkedAt = date
            connection.updatedAt = Date()
            try connection.update(db)
        }
    }

    // MARK: - Seed

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
