import Foundation

/// Why NowFocus closed an app. The block screen words itself from this.
public enum BlockReason {
    case focusSession, bedtime, dailyLimit, commitmentShield
}

/// What the user is told when NowFocus closes an app: who did it (always NowFocus, by name) and which
/// rule it was. Same sentences as Android's BlockCopy.kt; keep them in step.
public enum BlockCopy {
    public static func title(appName: String?) -> String {
        "NowFocus closed \(appName ?? "this app")"
    }

    /// `until` is already formatted for the user. `limitMinutes` is only read for `.dailyLimit`.
    public static func reason(_ reason: BlockReason, until: String, appName: String?, limitMinutes: Int = 0) -> String {
        switch reason {
        case .focusSession: return "You're in a focus session until \(until)."
        case .bedtime: return "It's bedtime wind-down until \(until)."
        case .commitmentShield: return "Locked by your Commitment Shield until \(until)."
        case .dailyLimit: return "You've used your \(limitMinutes) minutes of \(appName ?? "this app") today. It's back at midnight."
        }
    }

    /// The label beside the countdown.
    public static func timeLabel(_ reason: BlockReason) -> String {
        switch reason {
        case .focusSession: return "Left in session"
        case .bedtime: return "Left in bedtime"
        case .commitmentShield: return "Locked for"
        case .dailyLimit: return "Back in"
        }
    }
}
