import Foundation
import CryptoKit

/// The sync rules, as pure functions over `SyncLocal`. The engine does the network and the store does the
/// transaction; this decides what the data means. Port of Android's `SyncLogic`, plus the macOS-only guard below.
///
/// Invariants worth knowing:
///  - A record is "dirty" when it differs from the server copy we last saw (or never reached the server). Dirtiness
///    is always recomputed from the data; `SyncMeta.dirtyAt` only supplies the TIME of the user's last change.
///  - Deletions are recorded where they happen (`stampDeleted`), never inferred from a profile being absent.
///  - Conflicts use the server's own rule, last-write-wins on `updatedAt`.
///  - A policy a running or scheduled session is using (`inUse`) is never deleted or weakened by pulled data:
///    recoverSession() ends a session whose profile is missing, and that would let another device lift a Strict or
///    Locked session. Our copy is kept and pushed back as the newer change. Merely being bedtime's profile does NOT
///    count: with no session running, an edit or delete on another device must apply (a bedtime window that is
///    running IS an active session, so it is covered).
enum SyncLogic {
    static let policyType = "policy"
    static let bedtimeType = "bedtime_settings"
    static let bedtimeId = "default"
    private static let deletedFingerprint = "deleted"

    // MARK: - write-time bookkeeping (called in the same transaction that saved the data)

    enum MetaEdit { case keep, set(SyncMeta), remove }

    static func stampSaved(meta m: SyncMeta?, old: BlockPolicy?, new: BlockPolicy, now: Int64) -> MetaEdit {
        if let m, !m.imported { return .keep }
        if let old, PolicyWire.same(old, new) { return .keep }          // untouched by this write
        var base = m ?? SyncMeta()
        // Every change while dirty moves the timestamp: an offline edit at 10:05 must beat another device's edit at 10:02.
        base.dirtyAt = policyDirty(new, base) ? now : nil
        base.deleted = false
        base.rejected = nil
        return .set(base)
    }

    static func stampDeleted(meta m: SyncMeta?, now: Int64) -> MetaEdit {
        guard var t = m, t.rawJson != nil else { return .remove }        // never reached the server: nothing to tell it
        if !t.imported { return .keep }
        t.deleted = true; t.dirtyAt = now; t.rejected = nil
        return .set(t)
    }

    /// nil = nothing to record.
    static func stampBedtime(meta m: SyncMeta?, old: BedtimeSettings, new: BedtimeSettings, now: Int64) -> SyncMeta? {
        if BedtimeWire.same(old, new) { return nil }
        var base = m ?? SyncMeta()
        base.dirtyAt = bedtimeDirty(new, base) ? now : nil
        base.rejected = nil
        return base
    }

    // MARK: - linking

    /// Same account: resume. A different account (or none before): start clean so nothing of another account's cursor
    /// or raw data leaks in.
    static func link(_ state: SyncState, userId: String) -> SyncState { state.userId == userId ? state : SyncState(userId: userId) }

    /// After deleting the account the server data is gone, local data stays, and the next link is a fresh merge.
    static func unlink() -> SyncState { SyncState() }

    // MARK: - pull

    static func applyPulled(_ local: SyncLocal, records: [ServerRecord], cursor: Int, inUse: Set<String>, now: Int64) -> SyncApplied {
        var cur = local
        var bedtimeChanged = false
        for r in records {
            switch r.type {
            case policyType: cur = applyPolicyRecord(cur, r, inUse: inUse, now: now)
            case bedtimeType:
                let (next, changed) = applyBedtimeRecord(cur, r)
                cur = next; bedtimeChanged = bedtimeChanged || changed
            default: break   // session, shield_item, user_settings: not synced by this build; the cursor still moves past them
            }
        }
        cur.state.cursor = max(cursor, cur.state.cursor)
        return SyncApplied(local: cur, bedtimeChanged: bedtimeChanged)
    }

    /// Run once, after the first full pull following a link. `referenced` are policy ids something still points at
    /// (a session or bedtime's profile): an untouched starter profile among them is never dropped.
    static func finishInitialPull(_ local: SyncLocal, referenced: Set<String>) -> SyncLocal {
        var cur = local
        let serverHasProfiles = cur.state.policies.values.contains { $0.rawJson != nil && !$0.deleted }
        if serverHasProfiles {
            // This Mac's untouched starter profile would only duplicate the account's own.
            cur.policies.removeAll { p in
                let id = p.id.lowercased()
                return cur.state.policies[id] == nil && p.isUntouchedSeed && !referenced.contains(id)
            }
        }
        cur.state.initialPullDone = true
        return cur
    }

    private static func applyPolicyRecord(_ local: SyncLocal, _ r: ServerRecord, inUse: Set<String>, now: Int64) -> SyncLocal {
        var cur = local
        let id = r.id.lowercased()
        let m = local.state.policies[id]
        let mine = local.policies.first { $0.id.lowercased() == id }
        let isInUse = inUse.contains(id)

        func removeLocal() { cur.policies.removeAll { $0.id.lowercased() == id } }
        func upsertLocal(_ p: BlockPolicy) {
            if let i = cur.policies.firstIndex(where: { $0.id.lowercased() == id }) { cur.policies[i] = p } else { cur.policies.append(p) }
        }
        /// Keep our copy and make it win on the server: it goes up as a newer change than whatever the server holds.
        func keepMineAndRepush(base: SyncMeta?, raw: String?) {
            var nm = base ?? SyncMeta()
            nm.rawJson = raw; nm.revision = r.revision; nm.deleted = false; nm.rejected = nil
            nm.dirtyAt = max(now, r.updatedAt + 1)
            cur.state.policies[id] = nm
        }

        if r.deleted {
            guard let mine else { cur.state.policies[id] = nil; return cur }            // nothing here; forget the bookkeeping
            // No bookkeeping for a profile we hold: we can't tell whether it was edited here, so keep it (it goes up as new).
            guard let m else { return cur }
            if m.deleted { cur.state.policies[id] = nil; return cur }                   // both sides agree it's gone
            if isInUse { keepMineAndRepush(base: m, raw: nil); return cur }
            if policyDirty(mine, m), m.rawJson != nil, (m.dirtyAt ?? 0) > r.updatedAt {
                // Edited here after it was deleted there: the edit wins and is pushed as a new record.
                var nm = m; nm.rawJson = nil; nm.revision = r.revision; nm.deleted = false
                cur.state.policies[id] = nm
                return cur
            }
            removeLocal(); cur.state.policies[id] = nil
            return cur
        }

        guard var raw = JSONKit.object(r.dataJson) else { return cur }                  // unreadable server data: ignore it
        raw["id"] = id
        if !PolicyWire.supported(raw) {
            // Keep the record (so it's never mistaken for a deletion) but don't import or enforce it.
            cur.state.policies[id] = SyncMeta(rawJson: r.dataJson, revision: r.revision, imported: false)
            if !isInUse { removeLocal() }
            return cur
        }
        let incoming = PolicyWire.toLocal(raw, createdAt: mine?.createdAt ?? Date())
        let fresh = SyncMeta(rawJson: r.dataJson, revision: r.revision)

        if let m, m.deleted {
            // Deleted here, edited elsewhere: whichever happened later wins.
            if (m.dirtyAt ?? 0) > r.updatedAt {
                var nm = m; nm.rawJson = r.dataJson; nm.revision = r.revision
                cur.state.policies[id] = nm
            } else {
                cur.state.policies[id] = fresh; upsertLocal(incoming)
            }
            return cur
        }
        guard let mine else { cur.state.policies[id] = fresh; upsertLocal(incoming); return cur }

        // Present on both sides. No bookkeeping yet (first sync) means the server wins; otherwise a newer local edit wins.
        if let m, policyDirty(mine, m), (m.dirtyAt ?? 0) > r.updatedAt {
            var nm = m; nm.rawJson = r.dataJson; nm.revision = r.revision
            cur.state.policies[id] = nm
            return cur
        }
        if isInUse, PolicyWire.weakens(old: mine, new: incoming) { keepMineAndRepush(base: m, raw: r.dataJson); return cur }
        cur.state.policies[id] = fresh; upsertLocal(incoming)
        return cur
    }

    private static func applyBedtimeRecord(_ local: SyncLocal, _ r: ServerRecord) -> (SyncLocal, Bool) {
        guard !r.deleted, let raw = JSONKit.object(r.dataJson) else { return (local, false) }
        var cur = local
        let incoming = BedtimeWire.toLocal(raw)
        if let m = local.state.bedtime, bedtimeDirty(local.bedtime, m), (m.dirtyAt ?? 0) > r.updatedAt {
            var nm = m; nm.rawJson = r.dataJson; nm.revision = r.revision
            cur.state.bedtime = nm
            return (cur, false)
        }
        let changed = !BedtimeWire.same(local.bedtime, incoming)
        cur.bedtime = incoming
        cur.state.bedtime = SyncMeta(rawJson: r.dataJson, revision: r.revision)
        return (cur, changed)
    }

    // MARK: - push

    static func planPush(_ local: SyncLocal, now: Int64) -> [Outgoing] {
        guard local.state.userId != nil, local.state.initialPullDone else { return [] }
        var out: [Outgoing] = []
        for p in local.policies {
            let id = p.id.lowercased()
            let m = local.state.policies[id]
            if let m, m.deleted || !m.imported { continue }
            guard policyDirty(p, m) else { continue }
            let json = JSONKit.text(PolicyWire.merge(p, into: m?.raw))
            let fp = fingerprint(PolicyWire.canonical(p), m?.rawJson)
            if m?.rejected == fp { continue }
            out.append(Outgoing(type: policyType, id: id, updatedAt: m?.dirtyAt ?? now, dataJson: json, deleted: false, fingerprint: fp))
        }
        for (id, m) in local.state.policies.sorted(by: { $0.key < $1.key }) where m.deleted && m.imported && m.rawJson != nil && m.rejected != deletedFingerprint {
            out.append(Outgoing(type: policyType, id: id, updatedAt: m.dirtyAt ?? now, dataJson: nil, deleted: true, fingerprint: deletedFingerprint))
        }
        let bm = local.state.bedtime
        if bedtimeDirty(local.bedtime, bm) {
            let json = JSONKit.text(BedtimeWire.merge(local.bedtime, into: bm?.raw))
            let fp = fingerprint(BedtimeWire.canonical(local.bedtime), bm?.rawJson)
            if bm?.rejected != fp {
                out.append(Outgoing(type: bedtimeType, id: bedtimeId, updatedAt: bm?.dirtyAt ?? now, dataJson: json, deleted: false, fingerprint: fp))
            }
        }
        return out
    }

    /// `sent` and `results` are matched by (type, id). A change the server didn't answer stays dirty and goes out again.
    static func applyPushResults(_ local: SyncLocal, sent: [Outgoing], results: [PushOutcome], inUse: Set<String>, now: Int64) -> SyncApplied {
        var cur = local
        var bedtimeChanged = false
        var rejected = 0
        for res in results {
            guard let out = sent.first(where: { $0.type == res.type && $0.id.lowercased() == res.id.lowercased() }) else { continue }
            let id = out.id.lowercased()
            switch res.status {
            case "applied":
                guard let rec = res.record else { continue }
                if out.type == policyType {
                    if rec.deleted { cur.state.policies[id] = nil } else { adoptAfterPush(&cur, rec) }
                } else if out.type == bedtimeType, let raw = JSONKit.object(rec.dataJson) {
                    cur.state.bedtime = settle(cur.state.bedtime, rec, localMatchesServer: BedtimeWire.same(cur.bedtime, BedtimeWire.toLocal(raw)))
                }
            case "stale":
                // The server already has this or newer: take its copy, unless a newer local edit slipped in meanwhile.
                guard let rec = res.record else { continue }
                if out.type == policyType {
                    cur = applyPolicyRecord(cur, rec, inUse: inUse, now: now)
                } else if out.type == bedtimeType {
                    let (next, changed) = applyBedtimeRecord(cur, rec)
                    cur = next; bedtimeChanged = bedtimeChanged || changed
                }
            default:
                // Rejected: remember the payload so it isn't resent until the user changes it.
                rejected += 1
                if out.type == policyType {
                    if var m = cur.state.policies[id] { m.rejected = out.fingerprint; cur.state.policies[id] = m }
                } else {
                    var m = cur.state.bedtime ?? SyncMeta(); m.rejected = out.fingerprint; cur.state.bedtime = m
                }
            }
        }
        return SyncApplied(local: cur, bedtimeChanged: bedtimeChanged, rejected: rejected)
    }

    private static func adoptAfterPush(_ cur: inout SyncLocal, _ rec: ServerRecord) {
        let id = rec.id.lowercased()
        var same = false
        if let mine = cur.policies.first(where: { $0.id.lowercased() == id }), let raw = JSONKit.object(rec.dataJson) {
            same = PolicyWire.same(mine, PolicyWire.toLocal(raw))
        }
        cur.state.policies[id] = settle(cur.state.policies[id], rec, localMatchesServer: same)
    }

    /// After the server accepted our data it becomes the new base. If the user edited again meanwhile, it stays dirty.
    private static func settle(_ old: SyncMeta?, _ rec: ServerRecord, localMatchesServer: Bool) -> SyncMeta {
        SyncMeta(rawJson: rec.dataJson, revision: rec.revision, dirtyAt: localMatchesServer ? nil : old?.dirtyAt)
    }

    /// Changes the server refused that are still waiting for the user to edit them.
    static func rejectedCount(_ state: SyncState) -> Int {
        state.policies.values.filter { $0.rejected != nil }.count + (state.bedtime?.rejected != nil ? 1 : 0)
    }

    // MARK: - dirtiness (always derived from the data)

    static func policyDirty(_ p: BlockPolicy, _ m: SyncMeta?) -> Bool {
        guard let raw = m?.raw else { return true }
        return !PolicyWire.same(p, PolicyWire.toLocal(raw))
    }

    /// Never-uploaded bedtime counts as dirty only when it isn't just the defaults.
    static func bedtimeDirty(_ b: BedtimeSettings, _ m: SyncMeta?) -> Bool {
        guard let raw = m?.raw else { return !BedtimeWire.same(b, BedtimeWire.defaults) }
        return !BedtimeWire.same(b, BedtimeWire.toLocal(raw))
    }

    /// Identifies "this content on top of this server base". Built from the content, not from the merged JSON: a new
    /// rule gets a fresh id every time it is merged, which would make every attempt look different.
    private static func fingerprint(_ content: String, _ base: String?) -> String {
        SHA256.hash(data: Data("\(content)\u{0}\(base ?? "")".utf8)).prefix(8).map { String(format: "%02x", $0) }.joined()
    }
}
