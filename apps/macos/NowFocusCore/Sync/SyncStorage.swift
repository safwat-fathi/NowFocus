import Foundation
import GRDB

extension Notification.Name {
    /// A local edit was recorded for sync (only posted while the device is linked to an account): sync soon.
    public static let nowFocusLocalChange = Notification.Name("nowFocusLocalChange")
    /// Sync changed profiles or bedtime on this device: anything showing them must reload. Posted on the main queue.
    public static let nowFocusSyncApplied = Notification.Name("nowFocusSyncApplied")
}

enum SyncSignals {
    static func localChanged() { post(.nowFocusLocalChange) }
    static func applied() { post(.nowFocusSyncApplied) }
    private static func post(_ name: Notification.Name) { DispatchQueue.main.async { NotificationCenter.default.post(name: name, object: nil) } }
}

// MARK: - tables

private struct SyncRecordRow: Codable, FetchableRecord, PersistableRecord {
    static let databaseTableName = "syncRecord"
    var type: String
    var id: String
    var rawJson: String?
    var revision: Int
    var dirtyAt: Int64?
    var deleted: Bool
    var imported: Bool
    var rejected: String?

    init(type: String, id: String, meta m: SyncMeta) {
        self.type = type; self.id = id; rawJson = m.rawJson; revision = m.revision; dirtyAt = m.dirtyAt
        deleted = m.deleted; imported = m.imported; rejected = m.rejected
    }

    var meta: SyncMeta { SyncMeta(rawJson: rawJson, revision: revision, dirtyAt: dirtyAt, deleted: deleted, imported: imported, rejected: rejected) }
}

private struct SyncStateRow: Codable, FetchableRecord, PersistableRecord {
    static let databaseTableName = "syncState"
    var id = 1
    var userId: String?
    var cursor = 0
    var initialPullDone = false
}

enum SyncTables {
    static func nowMs() -> Int64 { SyncTime.nowMs() }

    static func userId(_ db: Database) throws -> String? {
        try SyncStateRow.fetchOne(db, key: 1)?.userId
    }

    static func meta(_ db: Database, _ type: String, _ id: String) throws -> SyncMeta? {
        try SyncRecordRow.fetchOne(db, key: ["type": type, "id": id])?.meta
    }

    /// nil removes the row.
    static func setMeta(_ db: Database, _ type: String, _ id: String, _ meta: SyncMeta?) throws {
        if let meta { try SyncRecordRow(type: type, id: id, meta: meta).save(db) }
        else { _ = try SyncRecordRow.deleteOne(db, key: ["type": type, "id": id]) }
    }

    static func loadState(_ db: Database) throws -> SyncState {
        var state = SyncState()
        if let row = try SyncStateRow.fetchOne(db, key: 1) {
            state.userId = row.userId; state.cursor = row.cursor; state.initialPullDone = row.initialPullDone
        }
        for row in try SyncRecordRow.fetchAll(db) {
            switch row.type {
            case SyncLogic.policyType: state.policies[row.id] = row.meta
            case SyncLogic.bedtimeType: state.bedtime = row.meta
            default: break
            }
        }
        return state
    }

    /// Writes only what differs from `old`.
    static func saveState(_ db: Database, old: SyncState, new: SyncState) throws {
        let hasRow = try SyncStateRow.fetchOne(db, key: 1) != nil
        if !hasRow || old.userId != new.userId || old.cursor != new.cursor || old.initialPullDone != new.initialPullDone {
            try SyncStateRow(userId: new.userId, cursor: new.cursor, initialPullDone: new.initialPullDone).save(db)
        }
        for id in old.policies.keys where new.policies[id] == nil { try setMeta(db, SyncLogic.policyType, id, nil) }
        for (id, m) in new.policies where old.policies[id] != m { try setMeta(db, SyncLogic.policyType, id, m) }
        if old.bedtime != new.bedtime { try setMeta(db, SyncLogic.bedtimeType, SyncLogic.bedtimeId, new.bedtime) }
    }
}

// MARK: - store

/// `SyncStore` over the app database. Pulled changes are applied with plain row writes, never through
/// `DatabaseManager.deletePolicy` (which cancels sessions) and never through `savePolicy` (which would stamp the
/// server's own data as a local edit).
final class DatabaseSyncStore: SyncStore {
    private let manager: DatabaseManager
    private let readBedtime: () -> BedtimeSettings
    private let writeBedtime: (BedtimeSettings) -> Void

    /// Bedtime lives in UserDefaults, not the database; the closures let a test give each simulated device its own.
    init(manager: DatabaseManager = .shared,
         readBedtime: @escaping () -> BedtimeSettings = { BedtimeSettingsStore.shared.settings },
         writeBedtime: @escaping (BedtimeSettings) -> Void = { BedtimeSettingsStore.shared.setFromSync($0) }) {
        self.manager = manager; self.readBedtime = readBedtime; self.writeBedtime = writeBedtime
    }

    func transact<R>(_ block: (SyncLocal) -> (SyncLocal, R)) throws -> R {
        var changed = false
        let result: R = try manager.dbQueue.write { db in
            let old = SyncLocal(policies: try BlockPolicyRecord.fetchAll(db).map { try $0.toPolicy() },
                                bedtime: readBedtime(), state: try SyncTables.loadState(db))
            let (new, result) = block(old)

            let oldById = Dictionary(old.policies.map { ($0.id.lowercased(), $0) }, uniquingKeysWith: { a, _ in a })
            let newIds = Set(new.policies.map { $0.id.lowercased() })
            for id in oldById.keys where !newIds.contains(id) { _ = try BlockPolicyRecord.deleteOne(db, key: id); changed = true }
            for p in new.policies {
                if let o = oldById[p.id.lowercased()], try Self.encoded(o) == Self.encoded(p) { continue }
                try BlockPolicyRecord(policy: p).save(db)
                changed = true
            }
            // Bedtime lives in UserDefaults, outside this transaction. Written first: if we crash before the bookkeeping
            // below, the next pass sees "local differs from the server copy" and re-sends data the server already has.
            // The other order would re-send the OLD local value over the server's newer one.
            if new.bedtime != old.bedtime {
                writeBedtime(new.bedtime)
                changed = true
            }
            try SyncTables.saveState(db, old: old.state, new: new.state)
            return result
        }
        if changed { SyncSignals.applied() }
        return result
    }

    func inUsePolicyIds() throws -> Set<String> {
        guard let session = try manager.fetchActiveSession() else { return [] }   // active or scheduled
        return [session.policyId.lowercased()]
    }

    func referencedPolicyIds() throws -> Set<String> {
        var ids = try inUsePolicyIds()
        if let id = readBedtime().policyId { ids.insert(id.lowercased()) }
        return ids
    }

    private static func encoded(_ p: BlockPolicy) throws -> Data {
        let e = JSONEncoder(); e.outputFormatting = .sortedKeys
        return try e.encode(p)
    }
}

extension DatabaseManager {
    /// Called by `BedtimeSettingsStore` after a user edit. A no-op until the device has been linked to an account.
    func recordBedtimeEdit(old: BedtimeSettings, new: BedtimeSettings) {
        var stamped = false
        do {
            try dbQueue.write { db in
                guard try SyncTables.userId(db) != nil else { return }
                if let meta = SyncLogic.stampBedtime(meta: try SyncTables.meta(db, SyncLogic.bedtimeType, SyncLogic.bedtimeId), old: old, new: new, now: SyncTables.nowMs()) {
                    try SyncTables.setMeta(db, SyncLogic.bedtimeType, SyncLogic.bedtimeId, meta)
                    stamped = true
                }
            }
        } catch {
            print("Failed to record the bedtime change for sync: \(error)")
        }
        if stamped { SyncSignals.localChanged() }
    }
}

extension DatabaseManager {
    /// The account this database is linked to, if any.
    func syncLinkedUserId() throws -> String? { try dbQueue.read { try SyncTables.userId($0) } }
}
