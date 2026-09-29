import Cocoa
import NowFocusCore
import Combine

public class AppBlocker {
    public static let shared = AppBlocker()
    
    private var cancellables = Set<AnyCancellable>()
    private var overlayPanels: [BlockOverlayPanel] = []
    
    private var activeBlockedApps: Set<String> = []
    private var isSessionActive: Bool = false
    private var blockedApp: NSRunningApplication?
    private var activeSessionId: String?

    // De-dup window for Stats' "turned away" count: a blocked app bounced
    // repeatedly within 3s of its last logged attempt is one attempt, not
    // several — a single "try to open it" gesture can fire more than one
    // foreground-change notification. Mirrors Android's shouldLogBlockEvent.
    private var lastLoggedBundleID: String?
    private var lastLoggedAt: Date?
    private let blockEventDedupWindow: TimeInterval = 3

    private init() {
        setupObservers()
    }

    public func updatePolicy(sessionId: String?, isSessionActive: Bool, blockedApps: [String]) {
        self.activeSessionId = sessionId
        self.isSessionActive = isSessionActive
        self.activeBlockedApps = Set(blockedApps)

        // Check current frontmost app immediately
        if let frontmost = NSWorkspace.shared.frontmostApplication {
            checkApplication(frontmost)
        } else {
            hideOverlay()
        }
    }
    
    private func setupObservers() {
        NSWorkspace.shared.notificationCenter.addObserver(
            self,
            selector: #selector(applicationDidActivate(_:)),
            name: NSWorkspace.didActivateApplicationNotification,
            object: nil
        )
    }
    
    @objc private func applicationDidActivate(_ notification: Notification) {
        guard let app = notification.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication else { return }
        checkApplication(app)
    }
    
    public func checkHealth() -> Bool {
        // ponytail: NSWorkspace activation notifications can't fail to register and need
        // no special permission, so there's no real failure mode to detect yet. If overlay
        // display ever starts depending on Accessibility/Screen Recording permission,
        // check that here instead of hardcoding true.
        return true
    }
    
    private func checkApplication(_ app: NSRunningApplication) {
        guard isSessionActive, let bundleID = app.bundleIdentifier else {
            hideOverlay()
            return
        }

        if activeBlockedApps.contains(bundleID) {
            blockedApp = app
            showOverlay()
            logBlockEvent(app: app, bundleID: bundleID)
        } else {
            hideOverlay()
        }
    }

    private func logBlockEvent(app: NSRunningApplication, bundleID: String) {
        guard let sessionId = activeSessionId else { return }

        let now = Date()
        if let lastLoggedAt, bundleID == lastLoggedBundleID, now.timeIntervalSince(lastLoggedAt) < blockEventDedupWindow {
            return
        }
        lastLoggedBundleID = bundleID
        lastLoggedAt = now

        let metadata: [String: String] = ["bundleId": bundleID, "name": app.localizedName ?? bundleID]
        guard let metadataJson = try? JSONSerialization.data(withJSONObject: metadata),
              let metadataString = String(data: metadataJson, encoding: .utf8) else { return }

        let event = SessionEvent(sessionId: sessionId, type: "app_blocked", occurredAt: now, metadataJson: metadataString)
        do {
            try DatabaseManager.shared.logEvent(event)
        } catch {
            print("Failed to log block event: \(error)")
        }
    }

    // Since we can't easily get the app's exact window rect without accessibility
    // permissions, cover every connected screen rather than guessing which one
    // has the blocked app's window — a single-screen overlay leaves the app
    // fully usable on any other display.
    private func showOverlay() {
        if overlayPanels.count != NSScreen.screens.count {
            overlayPanels.forEach { $0.hide() }
            overlayPanels = NSScreen.screens.map { _ in
                let panel = BlockOverlayPanel()
                panel.onClose = { self.closeBlockedApp() }
                return panel
            }
        }
        for (panel, screen) in zip(overlayPanels, NSScreen.screens) {
            // visibleFrame excludes the menu bar and Dock, so the user is never
            // trapped needing Cmd-Tab to reach NowFocus's own menu.
            panel.show(over: screen.visibleFrame)
        }
    }

    private func hideOverlay() {
        overlayPanels.forEach { $0.hide() }
        blockedApp = nil
    }

    // Graceful quit, not forceTerminate — respects the app's own quit sequence
    // (e.g. save-changes prompts). If the app declines to quit, frontmost app
    // doesn't change, no didActivateApplicationNotification fires, and the
    // overlay correctly stays up — no separate hide path needed here.
    private func closeBlockedApp() {
        blockedApp?.terminate()
    }
}
