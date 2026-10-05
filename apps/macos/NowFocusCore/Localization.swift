import Foundation

private final class CoreBundleToken {}

extension Bundle {
    /// NowFocusCore's own bundle, where its Localizable catalog lives; `Bundle.main` would be the app's.
    /// In the headless CoreChecks build there is no framework, so this is the main bundle and the English
    /// keys come back as written.
    static let nowFocusCore = Bundle(for: CoreBundleToken.self)
}

public extension Locale {
    /// The locale numbers and dates are formatted in: Arabic with Latin digits (`numbers=latn`) when this launch
    /// is in Arabic, otherwise the current one. Without it, Arabic would print 0-9 as ٠-٩.
    static var nowFocusUI: Locale {
        Bundle.main.preferredLocalizations.first == "ar" ? Locale(identifier: "ar@numbers=latn") : Locale.current
    }
}
