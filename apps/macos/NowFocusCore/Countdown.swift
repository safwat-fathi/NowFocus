import Foundation

/// The menu bar's remaining-time text: "24:35", or "1:02:05" from one hour up. Clamped at zero.
public enum Countdown {
    public static func text(remaining seconds: TimeInterval) -> String {
        let total = max(0, Int(seconds.rounded(.up)))
        let h = total / 3600, m = (total % 3600) / 60, s = total % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }
}
