import Foundation
import AppKit

/// What the user picked in Settings. macOS reads `AppleLanguages` once, at launch, so a change takes a relaunch.
enum AppLanguageChoice: String, CaseIterable {
    case system, en, ar
}

enum AppLanguage {
    /// The app's own `AppleLanguages` (the one this app wrote), not the system's: `nil` there means "follow the Mac".
    static var choice: AppLanguageChoice {
        let domain = UserDefaults.standard.persistentDomain(forName: Bundle.main.bundleIdentifier ?? "")
        switch (domain?["AppleLanguages"] as? [String])?.first {
        case "ar": return .ar
        case "en": return .en
        default: return .system
        }
    }

    static func set(_ choice: AppLanguageChoice) {
        switch choice {
        case .system: UserDefaults.standard.removeObject(forKey: "AppleLanguages")
        case .en: UserDefaults.standard.set(["en"], forKey: "AppleLanguages")
        case .ar: UserDefaults.standard.set(["ar"], forKey: "AppleLanguages")
        }
        UserDefaults.standard.synchronize()
    }

    /// True when this launch is showing Arabic, whichever way it got there.
    static var isArabic: Bool { Bundle.main.preferredLocalizations.first == "ar" }

    /// Arabic with Latin digits (`numbers=latn`), as on the website: sentences stay Arabic, but dates, counts and
    /// timers print 0-9. Apply with `.environment(\.locale, AppLanguage.locale)` at each root view.
    static var locale: Locale {
        isArabic ? Locale(identifier: "ar@numbers=latn") : Locale.current
    }

    /// Opens a fresh copy of the app and quits this one. The caller must not do this while a session is running:
    /// `applicationShouldTerminate` would refuse, and quitting then would drop app blocking.
    static func relaunch() {
        let task = Process()
        task.executableURL = URL(fileURLWithPath: "/usr/bin/open")
        task.arguments = ["-n", Bundle.main.bundlePath]
        try? task.run()
        NSApp.terminate(nil)
    }
}
