import Cocoa
import NowFocusCore

/// Closes the front browser's tab when it is on a page one of the session's feed rules names (YouTube Shorts,
/// Instagram Reels / Explore, Facebook Reels). A Mac cannot see inside a page, so this asks the browser for the
/// address of its active tab (AppleScript, which needs the user's one-time Automation approval per browser),
/// matches it in memory with `FeedURLMatcher` and closes the tab. The address is never stored, logged or sent;
/// only the rule's label goes into the block event.
///
/// Safari, Chrome, Brave, Edge and Arc. Firefox has no scriptable tab address, so it is not covered.
/// Not tamper-proof: another browser, or turning Automation off, gets around it, and the profile screen says so.
@MainActor
final class BrowserGuard: ObservableObject {
    static let shared = BrowserGuard()

    /// A browser said no: the user has to switch NowFocus on under Privacy & Security → Automation.
    @Published private(set) var needsAutomation = false

    /// Bundle id → (address of the front window's active tab, close it). Chromium browsers share one dictionary.
    private static let chromium = (url: "URL of active tab of front window", close: "close active tab of front window")
    private static let browsers: [String: (url: String, close: String)] = [
        "com.apple.Safari": (url: "URL of current tab of front window", close: "close current tab of front window"),
        "com.google.Chrome": chromium,
        "com.brave.Browser": chromium,
        "com.microsoft.edgemac": chromium,
        "company.thebrowser.Browser": chromium,
    ]
    private static let pollInterval: TimeInterval = 0.75

    private var rules: Set<String> = []
    private var sessionId: String?
    private var timer: Timer?
    private var gate = CloseGate()
    private var asked = Set<String>()
    private var denied = Set<String>()

    private init() {}

    /// The session's feed rules (wire names, already limited to what a Mac enforces). Empty switches the guard off.
    func update(sessionId: String?, rules: [String]) {
        self.sessionId = sessionId
        self.rules = Set(rules).intersection(FeedRules.enforceable)
        guard !self.rules.isEmpty, sessionId != nil else {
            timer?.invalidate()
            timer = nil
            denied.removeAll()
            needsAutomation = false
            return
        }
        guard timer == nil else { return }
        let t = Timer(timeInterval: Self.pollInterval, repeats: true) { _ in
            MainActor.assumeIsolated { BrowserGuard.shared.tick() }
        }
        RunLoop.main.add(t, forMode: .common)
        timer = t
    }

    private func tick() {
        guard let id = NSWorkspace.shared.frontmostApplication?.bundleIdentifier, let scripts = Self.browsers[id] else { return }
        guard hasPermission(id) else { return }
        guard let address = run("tell application id \"\(id)\" to return \(scripts.url)", id: id),
              let rule = FeedURLMatcher.rule(for: address), rules.contains(rule), gate.allow() else { return }
        _ = run("tell application id \"\(id)\" to \(scripts.close)", id: id)
        logBlock(rule)
    }

    /// Whether this browser may be scripted. The first time it asks the system to show the approval dialog, off
    /// the main thread (it waits for the user); until they answer, and while it is denied, nothing is read.
    private func hasPermission(_ bundleId: String) -> Bool {
        let target = NSAppleEventDescriptor(bundleIdentifier: bundleId)
        let status = AEDeterminePermissionToAutomateTarget(target.aeDesc, typeWildCard, typeWildCard, false)
        switch status {
        case noErr:
            denied.remove(bundleId)
            needsAutomation = !denied.isEmpty
            return true
        case OSStatus(errAEEventWouldRequireUserConsent):
            if asked.insert(bundleId).inserted {
                DispatchQueue.global(qos: .userInitiated).async {
                    _ = AEDeterminePermissionToAutomateTarget(target.aeDesc, typeWildCard, typeWildCard, true)
                }
            }
            return false
        case OSStatus(errAEEventNotPermitted):
            denied.insert(bundleId)
            needsAutomation = true
            return false
        default:
            return false   // the browser isn't running or answering; try again on the next poll
        }
    }

    /// Runs one line of AppleScript with a 2 s ceiling, so a hung browser can't freeze NowFocus. A missing window
    /// or a page without an address is an ordinary "no", not an error to surface.
    private func run(_ line: String, id: String) -> String? {
        var error: NSDictionary?
        let script = NSAppleScript(source: "with timeout of 2 seconds\n\(line)\nend timeout")
        let result = script?.executeAndReturnError(&error)
        if let code = error?[NSAppleScript.errorNumber] as? Int, code == Int(errAEEventNotPermitted) {
            denied.insert(id)
            needsAutomation = true
        }
        return error == nil ? result?.stringValue : nil
    }

    private func logBlock(_ rule: String) {
        guard let sessionId, let label = FeedRules.all.first(where: { $0.id == rule })?.label else { return }
        // Same shape as an app block ("bundleId" + "name"), so Stats counts it with the apps turned away.
        let metadata: [String: String] = ["bundleId": "feed:\(rule)", "name": label]
        guard let data = try? JSONSerialization.data(withJSONObject: metadata),
              let json = String(data: data, encoding: .utf8) else { return }
        let event = SessionEvent(sessionId: sessionId, type: "app_blocked", occurredAt: Date(), metadataJson: json)
        do { try DatabaseManager.shared.logEvent(event) } catch { print("Failed to log feed block: \(error)") }
    }
}
