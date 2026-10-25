import Foundation

/// Free vs Pro. The server owns the plan; this app only keeps the last answer it got (docs/pro-launch.md).
public enum Plan {
    /// Off until Pro launches: while false every feature stays open, signed in or not. Flipped in the release that
    /// goes out together with the API's `PRO_ENFORCED=1`.
    public static let enforced = false

    /// `stored` is the account's last answer: true Pro, false Free, nil when nobody is signed in or it has not been
    /// asked yet. Both nil and false count as Free once the gate is enforced.
    public static func isPro(_ stored: Bool?, enforced: Bool = Plan.enforced) -> Bool {
        !enforced || stored == true
    }
}
