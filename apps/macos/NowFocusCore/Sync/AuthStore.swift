import Foundation
import Security

/// Who this device is signed in as. The refresh token is the only secret here; it is single-use, so every refresh
/// replaces it (see `SyncAPI`).
struct StoredAuth: Codable, Equatable {
    var userId: String
    var email: String
    var deviceId: String
    var refreshToken: String
}

protocol AuthStore {
    /// nil when there is nothing stored, or it can't be read (denied, locked, damaged): the user simply signs in again.
    func load() -> StoredAuth?
    func save(_ auth: StoredAuth) throws
    func clear()
}

struct KeychainError: Error { let status: OSStatus }

/// One generic-password item holding the whole blob, so a rebuilt (ad-hoc signed) app costs one keychain prompt, not
/// four. Platform secure storage only, as services/api/WIRE_FORMAT.md requires.
final class KeychainAuthStore: AuthStore {
    private let service: String
    private let account = "sync"

    init(service: String = "app.getnowfocus.sync") { self.service = service }

    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
    }

    func load() -> StoredAuth? {
        var q = query
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return try? JSONDecoder().decode(StoredAuth.self, from: data)
    }

    func save(_ auth: StoredAuth) throws {
        let data = try JSONEncoder().encode(auth)
        var status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var add = query
            add[kSecValueData as String] = data
            #if os(iOS)
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            #endif
            status = SecItemAdd(add as CFDictionary, nil)
        }
        if status != errSecSuccess { throw KeychainError(status: status) }
    }

    func clear() { SecItemDelete(query as CFDictionary) }
}
