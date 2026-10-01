@testable import NowFocusCore
import Foundation

// Runs the real client against a real services/api, with scratch databases (never the app's own). See live-sync-check.sh.
// Field names, enum casing, ISO formats, close codes and URLSession's WebSocket behaviour can only be proven wrong
// against the real server.

var failures = 0
func check(_ ok: @autoclosure () throws -> Bool, _ message: String, line: Int = #line) {
    if (try? ok()) == true { print("PASS \(message)") } else { print("FAIL line \(line): \(message)"); failures += 1 }
}
func checkWait(_ seconds: Double, _ message: String, line: Int = #line, _ condition: @MainActor () -> Bool) async {
    let ok = await waitFor(seconds, condition)
    check(ok, message, line: line)
}
func checkAsync(_ message: String, line: Int = #line, _ body: () async throws -> Bool) async {
    let ok = (try? await body()) == true
    check(ok, message, line: line)
}
func section(_ title: String) { print("== \(title)") }

enum Cfg {
    static let baseURL: URL = ProcessInfo.processInfo.environment["SYNC_IT_URL"].flatMap(URL.init(string:)) ?? URL(string: "http://127.0.0.1:1")!
    static let idleSeconds = Int(ProcessInfo.processInfo.environment["LIVE_IDLE"] ?? "") ?? 0
    static let ua = "NowFocus-macos/it"
    static let password = "pw-pw-pw-pw"
}
if ProcessInfo.processInfo.environment["SYNC_IT_URL"] == nil {
    print("set SYNC_IT_URL to a running services/api, e.g. http://127.0.0.1:3996"); exit(2)
}

final class MemoryAuth: AuthStore {
    var stored: StoredAuth?
    func load() -> StoredAuth? { stored }
    func save(_ auth: StoredAuth) throws { stored = auth }
    func clear() { stored = nil }
}

/// One simulated Mac: its own scratch database, bedtime and tokens.
final class Phone {
    let name: String
    let manager: DatabaseManager
    let store: DatabaseSyncStore
    let auth = MemoryAuth()
    let api: SyncAPI
    let engine: SyncEngine
    let socket: SyncSocket

    init(_ name: String) {
        self.name = name
        let path = NSTemporaryDirectory() + "nf-live-\(UUID().uuidString).sqlite"
        manager = DatabaseManager(databasePath: path)
        api = SyncAPI(baseURL: Cfg.baseURL, userAgent: Cfg.ua, auth: auth)
        socket = SyncSocket(baseURL: Cfg.baseURL, userAgent: Cfg.ua)
        // Bedtime closures capture a box so the store and the test see the same value.
        let box = BedtimeBox()
        bedtimeBox = box
        store = DatabaseSyncStore(manager: manager, readBedtime: { box.value }, writeBedtime: { box.value = $0 })
        engine = SyncEngine(store: store, api: api)
    }
    let bedtimeBox: BedtimeBox

    var policies: [BlockPolicy] { try! manager.fetchAllPolicies() }
    var bedtimeNow: BedtimeSettings { bedtimeBox.value }

    func link(_ s: AccountSession) throws {
        try store.transact { l in var l = l; l.state = SyncLogic.link(l.state, userId: s.userId); return (l, ()) }
    }
    func sync() async throws -> SyncReport { try await engine.syncOnce() }
    func setBedtime(_ new: BedtimeSettings) {
        let old = bedtimeBox.value
        bedtimeBox.value = new
        manager.recordBedtimeEdit(old: old, new: new)      // what BedtimeSettingsStore's setter does
    }
    func pending() throws -> Int { try store.transact { l in (l, SyncLogic.planPush(l, now: SyncTime.nowMs()).count) } }
    func tick() async { try? await Task.sleep(for: .milliseconds(8)) }   // updatedAt is last-write-wins at ms resolution
}
final class BedtimeBox { var value = BedtimeSettings() }

func serverCopy(_ phone: Phone, type: String, id: String) async throws -> JSONObject? {
    let page = try await phone.api.pull(cursor: 0, limit: 500)
    return page.changes.first { $0.type == type && $0.id.lowercased() == id }.flatMap { JSONKit.object($0.dataJson) }
}

func waitFor(_ seconds: Double, _ condition: @MainActor () -> Bool) async -> Bool {
    let end = Date().addingTimeInterval(seconds)
    while Date() < end { if await condition() { return true }; try? await Task.sleep(for: .milliseconds(100)) }
    return await condition()
}

let policyId = "11111111-1111-4111-8111-111111111111"
let stamp = Int(Date().timeIntervalSince1970)

@MainActor
func runSync() async {
    var cleanup: [Phone] = []
    do {
        let email = "it-mac-\(stamp)@example.com"
        let a = Phone("A"), b = Phone("B")
        cleanup.append(a)
        try a.link(try await a.api.register(email: email, password: Cfg.password, deviceName: "Test Mac A"))
        try b.link(try await b.api.login(email: email, password: Cfg.password, deviceName: "Test Mac B"))

        // ---------------------------------------------------------------------------------------- 1. two Macs converge
        section("two Macs converge through the real API")
        let deviceList = try await a.api.devices()
        check(deviceList.filter { !$0.revoked }.count == 2, "both devices are listed")
        try a.manager.savePolicy(BlockPolicy(id: policyId, name: "Work", domains: [DomainRule(domain: "https://WWW.Example.com/x"), DomainRule(domain: "reddit.com")]))
        await a.tick()
        a.setBedtime({ var s = BedtimeSettings(); s.enabled = true; s.sleepMinute = 1400; s.policyId = policyId.uppercased(); return s }())
        let up = try await a.sync()
        check(up.pushed >= 2 && up.rejected == 0, "A pushed the profile and bedtime, nothing rejected (\(up))")
        _ = try await b.sync()
        let seen = b.policies
        check(seen.count == 1 && seen[0].name == "Work", "B received the profile")
        check(Set(seen.first?.domains.map(\.domain) ?? []) == ["example.com", "reddit.com"], "the messy domain arrived normalized")
        check(b.bedtimeNow.sleepMinute == 1400 && b.bedtimeNow.enabled && b.bedtimeNow.policyId == policyId, "B received bedtime, profile id lowercased")
        _ = try await a.sync(); _ = try await b.sync()
        check(try a.pending() == 0 && b.pending() == 0, "steady state: nothing left to send on either Mac")

        await b.tick()
        var edited = b.policies[0]; edited.name = "Work (B)"; edited.domains.append(DomainRule(domain: "news.ycombinator.com"))
        try b.manager.savePolicy(edited)
        _ = try await b.sync(); _ = try await a.sync()
        check(a.policies.map(\.name) == ["Work (B)"] && a.policies[0].domains.count == 3, "B's edit reached A")

        // -------------------------------------------------------------------------- 2. another platform's fields survive
        section("another platform's data survives a Mac edit")
        let androidId = "22222222-2222-4222-8222-222222222222"
        let androidShaped = "{\"id\":\"\(androidId)\",\"name\":\"From Android\",\"mode\":\"blocklist\"," +
            "\"domainRules\":[{\"id\":\"d1\",\"domain\":\"youtube.com\",\"includeSubdomains\":true,\"enabled\":true}]," +
            "\"applicationRules\":[{\"id\":\"a1\",\"platform\":\"android\",\"nativeIdentifier\":\"com.google.android.youtube\",\"displayName\":\"YouTube\",\"enabled\":true}]," +
            "\"partial\":[\"YT_SHORTS\",\"FB_REELS\"],\"categories\":[\"social\"],\"feedRules\":{\"v\":2},\"futureField\":{\"a\":[1,2]}}"
        let pushed = try await b.api.push([Outgoing(type: "policy", id: androidId, updatedAt: SyncTime.nowMs(), dataJson: androidShaped, deleted: false, fingerprint: "t")])
        check(pushed.first?.status == "applied", "the Android-shaped record was accepted (\(pushed.first?.status ?? "?") \(pushed.first?.code ?? ""))")
        _ = try await a.sync()
        guard var fromAndroid = a.policies.first(where: { $0.id == androidId }) else { check(false, "A received the Android-shaped policy"); return }
        check(fromAndroid.applications.isEmpty, "the Android app rule is not imported on a Mac")
        await a.tick()
        fromAndroid.name = "Renamed on Mac"; fromAndroid.domains.append(DomainRule(domain: "tiktok.com"))
        try a.manager.savePolicy(fromAndroid)
        _ = try await a.sync()
        let server = try await serverCopy(a, type: "policy", id: androidId)
        check(JSONKit.string(server ?? [:], "name") == "Renamed on Mac" && JSONKit.objects(server ?? [:], "domainRules").count == 2, "the Mac's edit reached the server")
        check(JSONKit.array(server ?? [:], "partial").compactMap { $0 as? String } == ["YT_SHORTS", "FB_REELS"], "partial names survived")
        check(JSONKit.objects(server ?? [:], "applicationRules").first.flatMap { JSONKit.string($0, "platform") } == "android", "Android's app rule survived")
        check((server?["futureField"] as? JSONObject) != nil && (server?["feedRules"] as? JSONObject) != nil && JSONKit.array(server ?? [:], "categories").count == 1, "unknown fields survived")
        // bedtime extras (Android's quietNotifications) survive a Mac bedtime edit
        let bedRec = try await serverCopy(a, type: "bedtime_settings", id: "default") ?? [:]
        var withQuiet = bedRec; withQuiet["quietNotifications"] = true
        _ = try await b.api.push([Outgoing(type: "bedtime_settings", id: "default", updatedAt: SyncTime.nowMs(), dataJson: JSONKit.text(withQuiet), deleted: false, fingerprint: "q")])
        _ = try await a.sync()
        await a.tick()
        var bed = a.bedtimeNow; bed.wakeMinute = 450
        a.setBedtime(bed)
        _ = try await a.sync()
        let bedAfter = try await serverCopy(a, type: "bedtime_settings", id: "default") ?? [:]
        check(JSONKit.int(bedAfter, "wakeMinute") == 450 && JSONKit.bool(bedAfter, "quietNotifications") == true, "a Mac bedtime edit kept quietNotifications")

        // a hostile pulled domain never reaches the model or the database
        let evil = "33333333-3333-4333-8333-333333333333"
        let evilPush = try await b.api.push([Outgoing(type: "policy", id: evil, updatedAt: SyncTime.nowMs(), dataJson: "{\"id\":\"\(evil)\",\"name\":\"Evil\",\"mode\":\"blocklist\",\"domainRules\":[{\"domain\":\"evil.com\\n1.2.3.4 bank.com\"}],\"applicationRules\":[]}", deleted: false, fingerprint: "e")])
        check(evilPush.first?.status == "rejected", "the server itself refuses a newline in a domain (\(evilPush.first?.status ?? "?") \(evilPush.first?.code ?? ""))")

        // ------------------------------------------------------------------------------------- 3. safety: pulled deletes
        section("a remote delete cannot remove a profile a session points at")
        _ = try await b.sync()
        try b.manager.deletePolicy(id: policyId)
        _ = try await b.sync()
        // A is running a session on that profile.
        try a.manager.saveSession(FocusSession(policyId: policyId, startAt: Date().addingTimeInterval(-60), endAt: Date().addingTimeInterval(3_600), status: .active, enforcementMode: .locked, deviceId: "local"))
        _ = try await a.sync()
        check(a.policies.contains { $0.id == policyId }, "A still has the profile after B deleted it")
        check(try a.manager.fetchActiveSession() != nil, "and A's session is untouched")
        _ = try await a.sync(); _ = try await b.sync()
        check(b.policies.contains { $0.id == policyId }, "A's copy was pushed back, so B has it again")
        check(try a.pending() == 0 && b.pending() == 0, "and everything is settled")
        if var running = try a.manager.fetchActiveSession() { running.status = .cancelled; try a.manager.saveSession(running) }

        // Bedtime merely pointing at a profile is not a lock: with no session running, a remote delete applies.
        let q = "44444444-4444-4444-8444-444444444444"
        try a.manager.savePolicy(BlockPolicy(id: q, name: "Bedtime profile", domains: [DomainRule(domain: "q.example.com")]))
        await a.tick()
        var pointed = a.bedtimeNow; pointed.policyId = q
        a.setBedtime(pointed)
        _ = try await a.sync(); _ = try await b.sync()
        check(b.policies.contains { $0.id == q }, "B has the profile bedtime points at")
        await b.tick()
        try b.manager.deletePolicy(id: q)
        _ = try await b.sync(); _ = try await a.sync()
        check(!a.policies.contains { $0.id == q }, "a remote delete of a profile only bedtime points at applies on A (no session running)")

        // ------------------------------------------------------------------------------------------------- 4. the socket
        section("the realtime socket")
        let events = EventLog()
        let token = try await a.api.accessTokenForSocket()
        let listener = Task { for await ev in a.socket.connect(accessToken: token) { events.add(ev) } }
        await checkWait(5, "the server sends the current cursor on connect") { events.cursor != nil }
        await b.tick()
        var again = b.policies.first { $0.id == policyId }!; again.name = "Socket nudge"
        try b.manager.savePolicy(again)
        let before = events.cursor ?? 0
        _ = try await b.sync()
        await checkWait(5, "a change on B nudges A's socket within seconds") { (events.cursor ?? 0) > before }
        if Cfg.idleSeconds > 0 {
            print("   (idling \(Cfg.idleSeconds)s: URLSession must answer the server's pings)")
            try? await Task.sleep(for: .seconds(Cfg.idleSeconds))
            check(events.closed == nil, "an idle socket survives \(Cfg.idleSeconds)s (no ping/pong drop)")
            let mark = events.cursor ?? 0
            await b.tick(); again.name = "After idle"; try b.manager.savePolicy(again); _ = try await b.sync()
            await checkWait(5, "and still receives changes afterwards") { (events.cursor ?? 0) > mark }
        }
        listener.cancel()

        // Revoke B from A: B's socket closes with 4403 and B's next sync fails as signed out.
        let bEvents = EventLog()
        let bToken = try await b.api.accessTokenForSocket()
        let bListener = Task { for await ev in b.socket.connect(accessToken: bToken) { bEvents.add(ev) } }
        await checkWait(5, "B's socket is connected") { bEvents.cursor != nil }
        try await a.api.revokeDevice(id: b.auth.stored!.deviceId)
        await checkWait(5, "revoking B closes its socket") { bEvents.closed != nil }
        check(bEvents.closed == 4403 || bEvents.sawRevoked, "with the revoked signal / close code 4403 (got \(String(describing: bEvents.closed)))")
        bListener.cancel()
        do { _ = try await b.sync(); check(false, "a revoked device must not keep syncing") } catch is AuthExpired { check(true, "a revoked device's next sync fails as signed out") }
        check(b.auth.stored == nil && !b.policies.isEmpty, "B forgot its tokens but kept its local data")

        // A refused upgrade (bad token) reports the HTTP status, not a silent hang.
        let bad = EventLog()
        let badTask = Task { for await ev in a.socket.connect(accessToken: "not-a-token") { bad.add(ev) } }
        await checkWait(5, "a bad token ends the connection") { bad.closed != nil }
        check(bad.closed == 401 || bad.closed == 4401, "and reports 401 / 4401 (got \(String(describing: bad.closed)))")
        badTask.cancel()

        // ------------------------------------------------------------------------------------------------- 5. refresh
        section("token refresh is single-flight and rotates the refresh token")
        let t0 = try await a.api.accessTokenForSocket()
        let rt0 = a.auth.stored!.refreshToken
        let tokens = try await withThrowingTaskGroup(of: String.self) { group -> [String] in
            for _ in 0..<5 { group.addTask { try await a.api.refreshedAccessToken(stale: t0) } }
            var out: [String] = []
            for try await t in group { out.append(t) }
            return out
        }
        // The access token is a JWT stamped in whole seconds, so a refresh inside the same second as the previous
        // token can legitimately produce an identical string. Single-flight is proven by the refresh token instead:
        // it is single-use, so a second concurrent refresh with the old one would have failed with AuthExpired above.
        check(Set(tokens).count == 1, "five concurrent refreshes all got the same token (\(Set(tokens).count) distinct)")
        check(a.auth.stored!.refreshToken != rt0, "the refresh token rotated exactly once and was saved")
        let rt1 = a.auth.stored!.refreshToken
        _ = try await a.api.refreshedAccessToken(stale: tokens[0])
        check(a.auth.stored!.refreshToken != rt1, "and a later, separate refresh rotates it again")
        let afterRefresh = try await a.api.devices()
        check(afterRefresh.count >= 2, "and the new token works")

    } catch {
        print("FAIL unexpected error: \(error)"); failures += 1
    }
    for p in cleanup { try? await p.api.deleteAccount(password: Cfg.password) }   // always leave the server clean
}

@MainActor
func runController() async {
    var cleanup: [Phone] = []
    do {
        // ------------------------------------------------------------------------------------------- 6. the controller
        section("two controllers keep each other up to date on their own")
        let emailY = "it-mac-ctl-\(stamp)@example.com"
        let c = Phone("C"), d = Phone("D")
        cleanup.append(c)
        let ctlC = SyncController(api: c.api, engine: c.engine, auth: c.auth, store: c.store, socket: c.socket, deviceName: "Controller C", debounce: .milliseconds(100))
        let ctlD = SyncController(api: d.api, engine: d.engine, auth: d.auth, store: d.store, socket: d.socket, deviceName: "Controller D", debounce: .milliseconds(100))
        await checkAsync("C creates an account") { await ctlC.signIn(email: emailY, password: Cfg.password, createAccount: true) == nil }
        await checkAsync("D signs in") { await ctlD.signIn(email: emailY, password: Cfg.password, createAccount: false) == nil }
        check(ctlC.status.signedIn && ctlC.status.email == emailY, "status shows the account")
        await checkAsync("a wrong password is reported in plain words") { await ctlC.signIn(email: emailY, password: "wrong-wrong", createAccount: false) == "Wrong email or password." }
        try c.manager.savePolicy(BlockPolicy(name: "From C", domains: [DomainRule(domain: "example.org")]))
        await checkWait(10, "a profile made on C appears on D by itself (socket nudge)") { d.policies.contains { $0.name == "From C" } }
        c.setBedtime({ var s = BedtimeSettings(); s.enabled = true; s.wakeMinute = 400; return s }())
        await checkWait(10, "a bedtime change on C appears on D by itself") { d.bedtimeNow.wakeMinute == 400 }
        await checkWait(10, "status reports a finished sync") { ctlC.status.lastSyncedAt != nil && !ctlC.status.syncing }
        let listed = try await ctlC.devices()
        check(listed.filter { !$0.revoked }.count == 2 && listed.filter(\.current).count == 1, "the device list shows both, marking this one")

        await ctlC.signOut()
        check(!ctlC.status.signedIn && c.auth.stored == nil, "sign out forgets the tokens")
        check(c.policies.contains { $0.name == "From C" }, "and keeps every local profile")
        try c.manager.savePolicy(BlockPolicy(name: "Offline edit", domains: [DomainRule(domain: "offline.example")]))
        try? await Task.sleep(for: .milliseconds(700))
        check(!d.policies.contains { $0.name == "Offline edit" }, "signed out: an edit goes nowhere (no traffic)")
        await checkAsync("deleting with a wrong password is refused") { await ctlD.deleteAccount(password: "wrong-wrong") == "Wrong password." }
        check(ctlD.status.signedIn, "and leaves D signed in")
        await checkAsync("deleting the account works") { await ctlD.deleteAccount(password: Cfg.password) == nil }
        check(!ctlD.status.signedIn && d.policies.contains { $0.name == "From C" }, "the Mac keeps its local data after deleting the account")
        check(try d.store.transact { l in (l, l.state.userId) } == nil, "and unlinks from the account")
        try await Task.sleep(for: .milliseconds(300))
    } catch {
        print("FAIL unexpected error: \(error)"); failures += 1
    }
    for p in cleanup { try? await p.api.deleteAccount(password: Cfg.password) }   // always leave the server clean
}

final class EventLog: @unchecked Sendable {
    private let lock = NSLock()
    private var _cursor: Int?, _closed: Int?, _revoked = false
    func add(_ e: SocketEvent) {
        lock.lock(); defer { lock.unlock() }
        switch e { case .changes(let c): _cursor = c; case .closed(let c): _closed = c; case .revoked: _revoked = true }
    }
    var cursor: Int? { lock.lock(); defer { lock.unlock() }; return _cursor }
    var closed: Int? { lock.lock(); defer { lock.unlock() }; return _closed }
    var sawRevoked: Bool { lock.lock(); defer { lock.unlock() }; return _revoked }
}

// ------------------------------------------------------------------------------------------ migration on real-shaped data
if let copy = ProcessInfo.processInfo.environment["NF_DB_COPY"] {
    section("migration v5 on a copy of a real database")
    let m = DatabaseManager(databasePath: copy)
    let policies = (try? m.fetchAllPolicies()) ?? []
    let sessions = (try? m.fetchSessions(from: .distantPast, to: .distantFuture)) ?? []
    print("   \(policies.count) profiles, \(sessions.count) sessions")
    check(!policies.isEmpty && policies.allSatisfy { $0.id == $0.id.lowercased() }, "every profile id is lowercase after the migration")
    check(sessions.allSatisfy { $0.policyId == $0.policyId.lowercased() }, "every session's profile id is lowercase too")
    check(try m.syncLinkedUserId() == nil, "a migrated database is not linked to any account (no bookkeeping, no traffic)")
    check(policies.allSatisfy { (try? m.fetchPolicy(id: $0.id.uppercased())) != nil }, "lookups still work with the old uppercase spelling")
}

// Auth routes allow 10 requests a minute per IP, so a full run is two parts with a server restart between them.
let part = ProcessInfo.processInfo.environment["LIVE_PART"] ?? "all"
if part == "all" || part == "1" { await runSync() }
if part == "all" || part == "2" { await runController() }
print(failures == 0 ? "DONE all live checks passed" : "DONE \(failures) live check(s) failed")
exit(failures == 0 ? 0 : 1)
