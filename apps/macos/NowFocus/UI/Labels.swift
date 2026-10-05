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
func sitesText(_ count: Int) -> String { loc("\(count) sites") }
func appsText(_ count: Int) -> String { loc("\(count) apps") }

/// "3 sites · 2 apps".
func sitesAppsSummary(sites: Int, apps: Int) -> String { "\(sitesText(sites)) · \(appsText(apps))" }

/// `String(localized:)` in the app's locale, so counts and times print Latin digits in Arabic.
func loc(_ value: String.LocalizationValue) -> String { String(localized: value, locale: AppLanguage.locale) }
func locr(_ resource: LocalizedStringResource) -> String {
    var r = resource
    r.locale = AppLanguage.locale
    return String(localized: r)
}
