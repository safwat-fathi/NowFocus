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
    
    private init() {
        setupObservers()
    }
    
    public func updatePolicy(isSessionActive: Bool, blockedApps: [String]) {
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
        } else {
            hideOverlay()
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
