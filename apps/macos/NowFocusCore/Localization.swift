import Foundation

private final class CoreBundleToken {}

extension Bundle {
    /// NowFocusCore's own bundle, where its Localizable catalog lives; `Bundle.main` would be the app's.
    /// In the headless CoreChecks build there is no framework, so this is the main bundle and the English
    /// keys come back as written.
    static let nowFocusCore = Bundle(for: CoreBundleToken.self)
}
