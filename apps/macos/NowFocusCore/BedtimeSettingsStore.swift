import Foundation

/// A handful of scalar settings, not history — UserDefaults, not a new SQLite
/// table.
public final class BedtimeSettingsStore {
    public static let shared = BedtimeSettingsStore()
    private let key = "NowFocusBedtimeSettings"
    private init() {}

    /// Writing is a user edit: once the device is linked to an account it is recorded for sync (see
    /// `DatabaseManager.recordBedtimeEdit`). Sync's own writes go through `setFromSync`, which are not edits.
    public var settings: BedtimeSettings {
        get { read() }
        set {
            let old = read()
            write(newValue)
            DatabaseManager.shared.recordBedtimeEdit(old: old, new: read())
        }
    }

    func setFromSync(_ newValue: BedtimeSettings) { write(newValue) }

    // Profile ids are lowercase everywhere (the server's spelling); settings saved before that stored them uppercase.
    private func read() -> BedtimeSettings {
        guard let data = UserDefaults.standard.data(forKey: key),
              var decoded = try? JSONDecoder().decode(BedtimeSettings.self, from: data) else {
            return BedtimeSettings()
        }
        decoded.policyId = decoded.policyId?.lowercased()
        return decoded
    }

    private func write(_ value: BedtimeSettings) {
        var value = value
        value.policyId = value.policyId?.lowercased()
        guard let data = try? JSONEncoder().encode(value) else { return }
        UserDefaults.standard.set(data, forKey: key)
    }
}
