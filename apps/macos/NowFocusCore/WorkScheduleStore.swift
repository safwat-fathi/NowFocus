import Foundation

// MARK: - Work Schedule

public struct WorkSchedule: Codable {
    /// Minutes since midnight for work start. Default: 9:00 AM (540).
    public var startMinute: Int = 9 * 60
    /// Minutes since midnight for work end. Default: 5:00 PM (1020).
    public var endMinute: Int = 17 * 60

    public init() {}
}

/// Persists work schedule settings in UserDefaults.
/// Mirrors BedtimeSettingsStore — same pattern, same rationale (small scalar
/// settings, not relational history data).
/// Collected during onboarding for future use (session-duration suggestions,
/// work-hours context display). No active consumers in v1.
public final class WorkScheduleStore {
    public static let shared = WorkScheduleStore()
    private let key = "NowFocusWorkSchedule"
    private init() {}

    public var schedule: WorkSchedule {
        get {
            guard let data = UserDefaults.standard.data(forKey: key),
                  let decoded = try? JSONDecoder().decode(WorkSchedule.self, from: data) else {
                return WorkSchedule()
            }
            return decoded
        }
        set {
            guard let data = try? JSONEncoder().encode(newValue) else { return }
            UserDefaults.standard.set(data, forKey: key)
        }
    }
}
