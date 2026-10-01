import Foundation

// Foundation-only on purpose: scripts/core-checks.sh compiles this folder's pure files without GRDB.
// See services/api/WIRE_FORMAT.md for the contract these types carry.

/// What the app knows about one synced record. Kept in the same SQLite file as the data, and written in the same
/// transaction as the data, so a local write and its bookkeeping are never seen apart.
struct SyncMeta: Equatable {
    /// The server's last `data` for this record, as JSON text; nil = this record never reached the server.
    var rawJson: String?
    var revision = 0
    /// Epoch ms of the latest local change that made the record differ from `rawJson`; becomes the push `updatedAt`.
    var dirtyAt: Int64?
    /// The user deleted it locally and the server hasn't been told yet.
    var deleted = false
    /// False for server records this Mac can't represent (allowlist profiles): kept in `rawJson`, never shown or enforced.
    var imported = true
    /// Fingerprint of a payload the server rejected, so it isn't resent until the user changes something.
    var rejected: String?

    var raw: JSONObject? { rawJson.flatMap(JSONKit.object) }
}

struct SyncState: Equatable {
    /// The account this device is linked to; nil = never signed in, so no bookkeeping is done at all.
    var userId: String?
    var cursor = 0
    /// False until the first full pull after linking has been applied; nothing is uploaded before that.
    var initialPullDone = false
    var policies: [String: SyncMeta] = [:]
    var bedtime: SyncMeta?
}

/// Everything the sync layer reads and writes in one atomic step.
struct SyncLocal {
    var policies: [BlockPolicy]
    var bedtime: BedtimeSettings
    var state: SyncState
}

/// A server record as it arrives from pull, or as the "current" copy inside a stale push result.
struct ServerRecord {
    var type: String
    var id: String
    var dataJson: String
    var deleted: Bool
    var revision: Int
    var updatedAt: Int64
}

/// One change to push. `dataJson` is nil for a tombstone. `updatedAt` is when the user made the change.
struct Outgoing {
    var type: String
    var id: String
    var updatedAt: Int64
    var dataJson: String?
    var deleted: Bool
    var fingerprint: String
}

struct PushOutcome {
    var type: String
    var id: String
    var status: String
    var record: ServerRecord?
    var code: String?
}

struct SyncApplied {
    var local: SyncLocal
    var bedtimeChanged = false
    var rejected = 0
}

/// `rejected` = changes the server refused that are still outstanding (not just in this pass).
public struct SyncReport {
    public var pulled: Int
    public var pushed: Int
    public var rejected: Int
}

// MARK: - JSON helpers

typealias JSONObject = [String: Any]

/// Null-safe accessors over `JSONSerialization` output. Edits are merged into the last server JSON, so unknown
/// fields must survive a round trip untouched: that is why this is dictionaries, not Codable models.
enum JSONKit {
    static func object(_ text: String) -> JSONObject? {
        guard let data = text.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? JSONObject
    }

    static func text(_ value: Any) -> String {
        guard JSONSerialization.isValidJSONObject(value),
              let data = try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys, .withoutEscapingSlashes]) else { return "{}" }
        return String(decoding: data, as: UTF8.self)
    }

    static func string(_ o: JSONObject, _ key: String) -> String? { o[key] as? String }

    static func bool(_ o: JSONObject, _ key: String) -> Bool? {
        guard let n = o[key] as? NSNumber, CFGetTypeID(n) == CFBooleanGetTypeID() else { return nil }
        return n.boolValue
    }

    static func int(_ o: JSONObject, _ key: String) -> Int? {
        guard let n = o[key] as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() else { return nil }
        return n.intValue
    }

    static func array(_ o: JSONObject, _ key: String) -> [Any] { o[key] as? [Any] ?? [] }
    static func objects(_ o: JSONObject, _ key: String) -> [JSONObject] { array(o, key).compactMap { $0 as? JSONObject } }
}

enum SyncTime {
    static func nowMs() -> Int64 { Int64((Date().timeIntervalSince1970 * 1000).rounded()) }

    private static func formatter(fraction: Bool) -> ISO8601DateFormatter {
        let f = ISO8601DateFormatter()
        f.formatOptions = fraction ? [.withInternetDateTime, .withFractionalSeconds] : [.withInternetDateTime]
        return f
    }

    static func ms(fromISO iso: String) -> Int64? {
        guard let d = formatter(fraction: true).date(from: iso) ?? formatter(fraction: false).date(from: iso) else { return nil }
        return Int64((d.timeIntervalSince1970 * 1000).rounded())
    }

    static func iso(fromMs ms: Int64) -> String {
        formatter(fraction: true).string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
    }
}
