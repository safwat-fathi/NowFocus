import Foundation
import Combine

public struct SyncStatus: Equatable {
    /// False until the stored sign-in has been read, so the UI doesn't flash "signed out".
    public var loaded = false
    public var signedIn = false
    public var email: String?
    public var syncing = false
    public var lastSyncedAt: Date?
    /// Human-readable, e.g. "Offline. Will retry." Nil when all is well.
    public var problem: String?
    /// Changes the server refused (they stay on this Mac and are retried only after you edit them).
    public var rejected = 0
}

public enum SyncConfig {
    public static let productionURL = URL(string: "https://api.nowfocus.online")!

    /// One build constant. Debug builds can point at a local server: `NOWFOCUS_API_URL=http://127.0.0.1:3000`.
    public static var baseURL: URL {
        #if DEBUG
        if let s = ProcessInfo.processInfo.environment["NOWFOCUS_API_URL"], let u = URL(string: s) { return u }
        #endif
        return productionURL
    }
}

/// Owns the account and keeps `SyncEngine` running: a pass after sign-in, after local edits (debounced), after a
/// WebSocket nudge, when the app comes back to the front, and on a growing delay after a failure. Signed out, it
/// makes no network calls at all: nothing is started until a stored sign-in exists or the user signs in.
///
/// Foundation/Combine only (no AppKit): the iOS app shares this file through NowFocusCore.
@MainActor
public final class SyncController: ObservableObject {
    @Published public private(set) var status = SyncStatus()

    private let engine: SyncEngine
    private let api: SyncAPI
    private let auth: AuthStore
    private let store: SyncStore
    private let socket: SyncSocket
    private let deviceName: String
    private let debounce: Duration

    private var session: Task<Void, Never>?
    private var trigger: AsyncStream<Void>.Continuation?
    private var localChangeObserver: NSObjectProtocol?

    public convenience init(baseURL: URL = SyncConfig.baseURL, userAgent: String, deviceName: String) {
        let auth = KeychainAuthStore()
        let api = SyncAPI(baseURL: baseURL, userAgent: userAgent, auth: auth)
        let store = DatabaseSyncStore()
        self.init(api: api, engine: SyncEngine(store: store, api: api), auth: auth, store: store,
                  socket: SyncSocket(baseURL: baseURL, userAgent: userAgent), deviceName: deviceName)
    }

    init(api: SyncAPI, engine: SyncEngine, auth: AuthStore, store: SyncStore, socket: SyncSocket, deviceName: String, debounce: Duration = .milliseconds(1500)) {
        self.api = api; self.engine = engine; self.auth = auth; self.store = store; self.socket = socket
        self.deviceName = deviceName; self.debounce = debounce
    }

    /// Reads the stored sign-in. Nothing touches the network unless one exists.
    /// The Keychain read happens off the main thread: after a rebuild macOS asks the user to approve it, and that
    /// prompt blocks the caller until it is answered, which must not freeze the whole app.
    public func start() {
        let auth = self.auth
        Task { [weak self] in
            let stored: StoredAuth? = await withCheckedContinuation { c in
                DispatchQueue.global(qos: .utility).async { c.resume(returning: auth.load()) }
            }
            guard let self else { return }
            self.status.loaded = true
            self.status.signedIn = stored != nil
            self.status.email = stored?.email
            if stored != nil { self.begin() }
        }
    }

    /// App back to the front, "Sync now", etc. A no-op while signed out.
    public func syncNow() { trigger?.yield() }

    /// Returns an error message for the user, or nil on success.
    public func signIn(email: String, password: String, createAccount: Bool) async -> String? {
        do {
            let s = createAccount ? try await api.register(email: email, password: password, deviceName: deviceName)
                                  : try await api.login(email: email, password: password, deviceName: deviceName)
            // A different account than before starts clean; the same one resumes where it left off.
            try store.transact { l in var l = l; l.state = SyncLogic.link(l.state, userId: s.userId); return (l, ()) }
            status = SyncStatus(loaded: true, signedIn: true, email: s.email)
            begin()
            return nil
        } catch is CancellationError { return nil } catch { return friendly(error) }
    }

    /// Local data and the Commitment Shield are never touched: signing out only forgets the tokens.
    public func signOut() async {
        end()
        await api.logout()
        status = SyncStatus(loaded: true)
    }

    /// Deletes the account and its server data; this Mac keeps everything it has. Returns an error message or nil.
    public func deleteAccount(password: String) async -> String? {
        do {
            try await api.deleteAccount(password: password)
            end()
            try store.transact { l in var l = l; l.state = SyncLogic.unlink(); return (l, ()) }
            status = SyncStatus(loaded: true)
            return nil
        } catch is CancellationError { return nil } catch { return friendly(error) }
    }

    public func devices() async throws -> [DeviceInfo] { try await api.devices() }

    public func revokeDevice(id: String) async -> String? {
        do { try await api.revokeDevice(id: id); return nil } catch is CancellationError { return nil } catch { return friendly(error) }
    }

    // MARK: -

    private func begin() {
        end()
        let (stream, continuation) = AsyncStream<Void>.makeStream(bufferingPolicy: .bufferingNewest(1))
        trigger = continuation
        localChangeObserver = NotificationCenter.default.addObserver(forName: .nowFocusLocalChange, object: nil, queue: .main) { _ in continuation.yield() }
        continuation.yield()   // the first pass
        session = Task { [weak self] in
            await withTaskGroup(of: Void.self) { group in
                group.addTask { await self?.passLoop(stream, continuation) }
                group.addTask { await self?.socketLoop(continuation) }
            }
        }
    }

    private func end() {
        session?.cancel(); session = nil
        trigger?.finish(); trigger = nil
        if let o = localChangeObserver { NotificationCenter.default.removeObserver(o); localChangeObserver = nil }
    }

    private func passLoop(_ stream: AsyncStream<Void>, _ continuation: AsyncStream<Void>.Continuation) async {
        var backoff = 0
        var first = true
        for await _ in stream {
            if !first { try? await Task.sleep(for: debounce) }   // coalesce a burst of edits into one pass
            first = false
            if Task.isCancelled { return }
            status.syncing = true
            do {
                let report = try await engine.syncOnce()
                backoff = 0
                status.syncing = false; status.lastSyncedAt = Date(); status.problem = nil; status.rejected = report.rejected
            } catch is CancellationError {
                return
            } catch is AuthExpired {
                await expired(); return
            } catch {
                backoff = backoff == 0 ? 5 : min(backoff * 2, 300)
                status.syncing = false
                status.problem = error is NetworkError ? "Offline. Will retry." : friendly(error)
                let wait = backoff
                Task { try? await Task.sleep(for: .seconds(wait)); continuation.yield() }
            }
        }
    }

    private func socketLoop(_ continuation: AsyncStream<Void>.Continuation) async {
        var wait = 1
        var refused: String?   // the token the server just rejected: the next connection must use a fresh one
        while !Task.isCancelled {
            do {
                let token: String
                if let stale = refused { token = try await api.refreshedAccessToken(stale: stale) } else { token = try await api.accessTokenForSocket() }
                refused = nil
                for await event in socket.connect(accessToken: token) {
                    switch event {
                    case .changes(let cursor):
                        wait = 1
                        if cursor > (try store.transact { l in (l, l.state.cursor) }) { continuation.yield() }
                    case .revoked: throw AuthExpired()
                    case .closed(let code):
                        if code == 4403 { throw AuthExpired() }
                        if code == 4401 || code == 401 { refused = token }   // access tokens last 15 minutes; a long-lived socket outlives one
                    }
                }
            } catch is CancellationError {
                return
            } catch is AuthExpired {
                await expired(); return
            } catch { /* offline etc.: just reconnect */ }
            try? await Task.sleep(for: .seconds(wait))
            wait = min(wait * 2, 60)
        }
    }

    private func expired() async {
        api.forget()
        end()
        status = SyncStatus(loaded: true, problem: "This device was signed out. Sign in again to keep syncing.")
    }

    func friendly(_ error: Error) -> String {
        switch error {
        case is NetworkError: return "Can't reach NowFocus. Check your connection and try again."
        case is AuthExpired: return "Your session ended. Sign in again."
        case let e as KeychainError: return "Couldn't save your sign-in to the Keychain (error \(e.status))."
        case let e as ApiError:
            switch (e.code, e.status) {
            case ("invalid_credentials", _): return "Wrong email or password."
            case ("email_taken", _): return "An account with this email already exists. Sign in instead."
            case ("wrong_password", _): return "Wrong password."
            case (_, 429): return "Too many attempts. Wait a minute and try again."
            case (_, 500...599): return "NowFocus is having trouble right now. Try again shortly."
            default: return e.message
            }
        default: return error.localizedDescription
        }
    }
}
