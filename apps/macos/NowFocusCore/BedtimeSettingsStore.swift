import Foundation

/// A handful of scalar settings, not history — UserDefaults, not a new SQLite
/// table.
public final class BedtimeSettingsStore {
    public static let shared = BedtimeSettingsStore()
    private let key = "NowFocusBedtimeSettings"
    private init() {}

    public var settings: BedtimeSettings {
        get {
            guard let data = UserDefaults.standard.data(forKey: key),
                  let decoded = try? JSONDecoder().decode(BedtimeSettings.self, from: data) else {
                return BedtimeSettings()
            }
            return decoded
        }
        set {
            guard let data = try? JSONEncoder().encode(newValue) else { return }
            UserDefaults.standard.set(data, forKey: key)
        }
    }
}
