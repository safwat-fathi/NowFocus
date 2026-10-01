import Foundation

/// The server said no. `code` is the stable machine-readable one (services/api/WIRE_FORMAT.md): branch on it, never on `message`.
struct ApiError: Error {
    let status: Int
    let code: String?
    let message: String
}

/// Could not reach the server at all (offline, DNS, TLS, timeout). Always safe to retry later.
struct NetworkError: Error { let underlying: Error }

/// The refresh token is dead (device revoked or token lost): the user has to sign in again.
struct AuthExpired: Error {}

struct AccountSession { let userId: String; let email: String; let deviceId: String }

public struct DeviceInfo: Identifiable {
    public let id: String
    public let name: String
    public let platform: String
    public let lastSeenAt: Date?
    public let current: Bool
    public let revoked: Bool
}

struct PullPage { let changes: [ServerRecord]; let cursor: Int; let hasMore: Bool }

/// What the sync engine needs from the network.
protocol SyncAPIPort {
    func pull(cursor: Int, limit: Int) async throws -> PullPage
    func push(_ changes: [Outgoing]) async throws -> [PushOutcome]
}

/// HTTP client for services/api. The access token lives in memory only; the refresh token goes through `AuthStore`.
/// Refresh is single-flight and the new refresh token is persisted BEFORE the new access token is used, because
/// refresh tokens are single-use: losing one signs the device out and creates a new device row on the next login.
final class SyncAPI: SyncAPIPort, @unchecked Sendable {
    private let base: URL
    private let userAgent: String
    private let auth: AuthStore
    private let session: URLSession
    private let refreshLock = AsyncLock()
    private let tokenLock = NSLock()
    private var token: String?

    init(baseURL: URL, userAgent: String, auth: AuthStore, session: URLSession = SyncAPI.defaultSession()) {
        self.base = baseURL; self.userAgent = userAgent; self.auth = auth; self.session = session
    }

    static func defaultSession() -> URLSession {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = 30
        c.waitsForConnectivity = false
        return URLSession(configuration: c)
    }

    private var accessToken: String? {
        get { tokenLock.lock(); defer { tokenLock.unlock() }; return token }
        set { tokenLock.lock(); token = newValue; tokenLock.unlock() }
    }

    // MARK: account

    func register(email: String, password: String, deviceName: String) async throws -> AccountSession { try await open("/v1/auth/register", email, password, deviceName) }
    func login(email: String, password: String, deviceName: String) async throws -> AccountSession { try await open("/v1/auth/login", email, password, deviceName) }

    private func open(_ path: String, _ email: String, _ password: String, _ deviceName: String) async throws -> AccountSession {
        let body: JSONObject = ["email": email.trimmingCharacters(in: .whitespacesAndNewlines), "password": password,
                                "device": ["name": deviceName, "platform": "macos"]]
        let res = try await sendJSON(request(path, method: "POST", body: body))
        guard let user = res["user"] as? JSONObject, let userId = JSONKit.string(user, "id"), let email = JSONKit.string(user, "email"),
              let deviceId = (res["device"] as? JSONObject).flatMap({ JSONKit.string($0, "id") }),
              let refresh = JSONKit.string(res, "refreshToken"), let access = JSONKit.string(res, "accessToken") else {
            throw ApiError(status: 200, code: nil, message: "Unexpected answer from the server.")
        }
        try auth.save(StoredAuth(userId: userId, email: email, deviceId: deviceId, refreshToken: refresh))
        accessToken = access
        return AccountSession(userId: userId, email: email, deviceId: deviceId)
    }

    /// Best effort: whatever the network says, this device forgets its tokens.
    func logout() async {
        _ = try? await authed(request("/v1/auth/logout", method: "POST", body: [:]))
        forget()
    }

    func forget() { accessToken = nil; auth.clear() }

    func deleteAccount(password: String) async throws {
        _ = try await authed(request("/v1/me/delete", method: "POST", body: ["password": password]))
        forget()
    }

    func devices() async throws -> [DeviceInfo] {
        let data = try await authed(request("/v1/devices", method: "GET"))
        let list = (try? JSONSerialization.jsonObject(with: data)) as? [Any] ?? []
        return list.compactMap { $0 as? JSONObject }.compactMap { o in
            guard let id = JSONKit.string(o, "id") else { return nil }
            return DeviceInfo(id: id, name: JSONKit.string(o, "name") ?? "", platform: JSONKit.string(o, "platform") ?? "",
                              lastSeenAt: JSONKit.string(o, "lastSeenAt").flatMap(SyncTime.ms(fromISO:)).map { Date(timeIntervalSince1970: Double($0) / 1000) },
                              current: JSONKit.bool(o, "current") ?? false, revoked: !(o["revokedAt"] == nil || o["revokedAt"] is NSNull))
        }
    }

    func revokeDevice(id: String) async throws { _ = try await authed(request("/v1/devices/\(id)", method: "DELETE")) }

    // MARK: sync

    func pull(cursor: Int, limit: Int) async throws -> PullPage {
        let res = try await authedJSON(request("/v1/sync/pull?cursor=\(cursor)&limit=\(limit)", method: "GET"))
        return PullPage(changes: JSONKit.objects(res, "changes").map(Self.record), cursor: JSONKit.int(res, "cursor") ?? cursor, hasMore: JSONKit.bool(res, "hasMore") ?? false)
    }

    func push(_ changes: [Outgoing]) async throws -> [PushOutcome] {
        let items: [JSONObject] = changes.map { c in
            var o: JSONObject = ["type": c.type, "id": c.id, "updatedAt": SyncTime.iso(fromMs: c.updatedAt), "data": c.dataJson.flatMap(JSONKit.object) ?? [:]]
            if c.deleted { o["deleted"] = true }
            return o
        }
        let res = try await authedJSON(request("/v1/sync/push", method: "POST", body: ["changes": items]))
        return JSONKit.objects(res, "results").map { o in
            PushOutcome(type: JSONKit.string(o, "type") ?? "", id: JSONKit.string(o, "id") ?? "", status: JSONKit.string(o, "status") ?? "rejected",
                        record: (o["record"] as? JSONObject).map(Self.record), code: JSONKit.string(o, "code"))
        }
    }

    /// A fresh access token for the WebSocket.
    func accessTokenForSocket() async throws -> String {
        if let t = accessToken { return t }
        try await refresh(stale: nil)
        guard let t = accessToken else { throw AuthExpired() }
        return t
    }

    /// The token the socket just used was refused (it expired): get a newer one.
    func refreshedAccessToken(stale: String) async throws -> String {
        try await refresh(stale: stale)
        guard let t = accessToken else { throw AuthExpired() }
        return t
    }

    // MARK: plumbing

    private static func record(_ o: JSONObject) -> ServerRecord {
        ServerRecord(type: JSONKit.string(o, "type") ?? "", id: JSONKit.string(o, "id") ?? "",
                     dataJson: JSONKit.text(o["data"] as? JSONObject ?? [:]), deleted: JSONKit.bool(o, "deleted") ?? false,
                     revision: JSONKit.int(o, "revision") ?? 0, updatedAt: JSONKit.string(o, "updatedAt").flatMap(SyncTime.ms(fromISO:)) ?? 0)
    }

    private func request(_ path: String, method: String, body: JSONObject? = nil) -> URLRequest {
        var r = URLRequest(url: URL(string: path, relativeTo: base)!.absoluteURL)
        r.httpMethod = method
        r.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        r.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            r.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
            r.httpBody = try? JSONSerialization.data(withJSONObject: body)
        }
        return r
    }

    private func sendJSON(_ req: URLRequest) async throws -> JSONObject {
        let data = try await send(req)
        return data.isEmpty ? [:] : ((try? JSONSerialization.jsonObject(with: data)) as? JSONObject ?? [:])
    }

    private func authedJSON(_ req: URLRequest) async throws -> JSONObject {
        let data = try await authed(req)
        return data.isEmpty ? [:] : ((try? JSONSerialization.jsonObject(with: data)) as? JSONObject ?? [:])
    }

    /// Runs an authenticated call: one transparent refresh on 401, then it gives up with `AuthExpired` or the server's error.
    private func authed(_ req: URLRequest) async throws -> Data {
        var current = accessToken
        if current == nil { try await refresh(stale: nil); current = accessToken }
        guard let first = current else { throw AuthExpired() }
        do { return try await send(bearer(req, first)) } catch let e as ApiError where e.status == 401 {}
        try await refresh(stale: first)
        guard let fresh = accessToken else { throw AuthExpired() }
        do { return try await send(bearer(req, fresh)) } catch let e as ApiError where e.status == 401 {
            forget(); throw AuthExpired()
        }
    }

    private func bearer(_ req: URLRequest, _ token: String) -> URLRequest {
        var r = req; r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization"); return r
    }

    /// `stale` is the access token that just failed: if someone else already replaced it, don't refresh again.
    private func refresh(stale: String?) async throws {
        try await refreshLock.withLock {
            if let stale, let now = accessToken, now != stale { return }
            guard var stored = auth.load() else { throw AuthExpired() }
            let res: JSONObject
            do {
                res = try await sendJSON(request("/v1/auth/refresh", method: "POST", body: ["refreshToken": stored.refreshToken]))
            } catch let e as ApiError where e.status == 401 {
                forget(); throw AuthExpired()
            }
            guard let refresh = JSONKit.string(res, "refreshToken"), let access = JSONKit.string(res, "accessToken") else {
                throw ApiError(status: 200, code: nil, message: "Unexpected answer from the server.")
            }
            stored.refreshToken = refresh
            try auth.save(stored)           // persist the new single-use token first
            accessToken = access
        }
    }

    private func send(_ req: URLRequest) async throws -> Data {
        let data: Data, response: URLResponse
        do {
            (data, response) = try await session.data(for: req)
        } catch {
            if Task.isCancelled || (error as? URLError)?.code == .cancelled { throw CancellationError() }
            throw NetworkError(underlying: error)
        }
        guard let http = response as? HTTPURLResponse else { throw NetworkError(underlying: URLError(.badServerResponse)) }
        guard (200..<300).contains(http.statusCode) else {
            let o = JSONKit.object(String(decoding: data, as: UTF8.self))
            var message = o.flatMap { JSONKit.string($0, "message") } ?? ""
            if message.isEmpty, let list = o?["message"] as? [Any] { message = list.compactMap { $0 as? String }.joined(separator: "; ") }
            throw ApiError(status: http.statusCode, code: o.flatMap { JSONKit.string($0, "code") }, message: message.isEmpty ? "HTTP \(http.statusCode)" : message)
        }
        return data
    }
}
