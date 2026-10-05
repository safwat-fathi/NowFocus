import Foundation

/// Why NowFocus closed an app. The block screen words itself from this.
public enum BlockReason {
    case focusSession, bedtime, dailyLimit, commitmentShield
}

/// What the user is told when NowFocus closes an app: who did it (always NowFocus, by name) and which
/// rule it was. Same sentences as Android's BlockCopy.kt; keep them in step.
public enum BlockCopy {
    public static func title(appName: String?) -> String {
        String(localized: "NowFocus closed \(appName ?? String(localized: "this app", bundle: .nowFocusCore))", bundle: .nowFocusCore)
    }

    /// `until` is already formatted for the user. `limitMinutes` is only read for `.dailyLimit`.
    public static func reason(_ reason: BlockReason, until: String, appName: String?, limitMinutes: Int = 0) -> String {
        switch reason {
        case .focusSession: return String(localized: "You're in a focus session until \(until).", bundle: .nowFocusCore)
        case .bedtime: return String(localized: "It's bedtime wind-down until \(until).", bundle: .nowFocusCore)
        case .commitmentShield: return String(localized: "Locked by your Commitment Shield until \(until).", bundle: .nowFocusCore)
        case .dailyLimit: return String(localized: "You've used your \(limitMinutes) minutes of \(appName ?? String(localized: "this app", bundle: .nowFocusCore)) today. It's back at midnight.", bundle: .nowFocusCore)
        }
    }

    /// The label beside the countdown.
    public static func timeLabel(_ reason: BlockReason) -> String {
        switch reason {
        case .focusSession: return String(localized: "Left in session", bundle: .nowFocusCore)
        case .bedtime: return String(localized: "Left in bedtime", bundle: .nowFocusCore)
        case .commitmentShield: return String(localized: "Locked for", bundle: .nowFocusCore)
        case .dailyLimit: return String(localized: "Back in", bundle: .nowFocusCore)
        }
    }
}
