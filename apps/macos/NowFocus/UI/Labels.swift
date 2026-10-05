import Foundation
import NowFocusCore

extension EnforcementMode {
    /// The mode's name in the app's language. `rawValue` is the stored id ("normal"), never shown.
    var displayName: String {
        switch self {
        case .normal: return String(localized: "Normal")
        case .strict: return String(localized: "Strict")
        case .locked: return String(localized: "Locked")
        }
    }
}

/// "3 sites" / "موقعان": each count in its own plural form (Arabic has six).
func sitesText(_ count: Int) -> String { String(localized: "\(count) sites") }
func appsText(_ count: Int) -> String { String(localized: "\(count) apps") }

/// "3 sites · 2 apps".
func sitesAppsSummary(sites: Int, apps: Int) -> String { "\(sitesText(sites)) · \(appsText(apps))" }
