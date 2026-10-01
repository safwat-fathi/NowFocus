import Foundation

/// What the sync engine needs from the local side. `transact` is one atomic read-modify-write of profiles, bedtime and
/// the bookkeeping together; the block is pure (see `SyncLogic`).
protocol SyncStore {
    func transact<R>(_ block: (SyncLocal) -> (SyncLocal, R)) throws -> R
    /// Profiles something still points at (the current session, bedtime's profile). Only guards the first-sign-in
    /// merge: an untouched starter profile among them is kept.
    func referencedPolicyIds() throws -> Set<String>
    /// Profiles a running or scheduled session is using. Pulled data may not delete or weaken these.
    func inUsePolicyIds() throws -> Set<String>
}

/// A mutex for async code. An `actor` is reentrant at every `await`, so it would NOT keep two token refreshes (or
/// two sync passes) from interleaving; this does. Waiters are served in order.
final class AsyncLock: @unchecked Sendable {
    private let state = NSLock()
    private var held = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func withLock<T>(_ body: () async throws -> T) async rethrows -> T {
        await acquire()
        defer { release() }
        return try await body()
    }

    private func acquire() async {
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
            state.lock()
            if held { waiters.append(c); state.unlock() } else { held = true; state.unlock(); c.resume() }
        }
    }

    private func release() {
        state.lock()
        if waiters.isEmpty { held = false; state.unlock() } else { let next = waiters.removeFirst(); state.unlock(); next.resume() }
    }
}

/// One sync pass: pull everything new, apply it, then push what changed here. Passes never overlap. Network errors
/// propagate untouched (the caller decides when to retry); local data is only ever changed through `SyncLogic`,
/// inside `SyncStore.transact`.
final class SyncEngine {
    private let store: SyncStore
    private let api: SyncAPIPort
    private let clock: () -> Int64
    private let lock = AsyncLock()

    init(store: SyncStore, api: SyncAPIPort, clock: @escaping () -> Int64 = SyncTime.nowMs) {
        self.store = store; self.api = api; self.clock = clock
    }

    func syncOnce() async throws -> SyncReport { try await lock.withLock { try await pass() } }

    private func pass() async throws -> SyncReport {
        var pulled = 0, pushed = 0

        // 1. Pull until caught up. Each page and its cursor are applied in one atomic step.
        for _ in 0..<Self.maxPages {
            let cursor = try store.transact { l in (l, l.state.userId == nil ? nil : l.state.cursor) }
            guard let cursor else { return SyncReport(pulled: 0, pushed: 0, rejected: 0) }   // signed out: no traffic at all
            let page = try await api.pull(cursor: cursor, limit: 500)
            let inUse = try store.inUsePolicyIds()
            try store.transact { l in
                l.state.userId == nil ? (l, ()) : (SyncLogic.applyPulled(l, records: page.changes, cursor: page.cursor, inUse: inUse, now: clock()).local, ())
            }
            pulled += page.changes.count
            if !page.hasMore || page.cursor <= cursor { break }
        }

        // 2. The first pull after linking is complete: decide what the account already has versus what only this Mac has.
        let referenced = try store.referencedPolicyIds()
        try store.transact { l in
            l.state.userId != nil && !l.state.initialPullDone ? (SyncLogic.finishInitialPull(l, referenced: referenced), ()) : (l, ())
        }

        // 3. Push. A stale answer can leave something new to send (a rebased edit), so look again, a few times at most.
        for _ in 0..<Self.maxPushRounds {
            let plan = try store.transact { l in (l, SyncLogic.planPush(l, now: clock())) }
            if plan.isEmpty { break }
            for start in stride(from: 0, to: plan.count, by: Self.maxChangesPerPush) {
                let chunk = Array(plan[start..<min(start + Self.maxChangesPerPush, plan.count)])
                let results = try await api.push(chunk)
                let inUse = try store.inUsePolicyIds()
                try store.transact { l in
                    l.state.userId == nil ? (l, ()) : (SyncLogic.applyPushResults(l, sent: chunk, results: results, inUse: inUse, now: clock()).local, ())
                }
                pushed += chunk.count
            }
        }
        let rejected = try store.transact { l in (l, SyncLogic.rejectedCount(l.state)) }
        return SyncReport(pulled: pulled, pushed: pushed, rejected: rejected)
    }

    private static let maxPages = 1000
    private static let maxPushRounds = 3
    private static let maxChangesPerPush = 100   // the server's per-request limit
}
