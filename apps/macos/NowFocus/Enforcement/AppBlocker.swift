import Cocoa
import NowFocusCore
import Combine

public class AppBlocker {
    public static let shared = AppBlocker()
    
    private var cancellables = Set<AnyCancellable>()
    private var overlayPanel = BlockOverlayPanel()
    
    private var activeBlockedApps: Set<String> = []
    private var isSessionActive: Bool = false
    
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
            overlayPanel.hide()
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
        // Simple heuristic: if we are initialized, we assume NSWorkspace notifications are working.
        // In a more complex setup we could track the last notification timestamp.
        return true
    }
    
    private func checkApplication(_ app: NSRunningApplication) {
        guard isSessionActive, let bundleID = app.bundleIdentifier else {
            overlayPanel.hide()
            return
        }
        
        if activeBlockedApps.contains(bundleID) {
            // App is blocked. Show overlay over the app's window area.
            // Since we can't easily get the app's exact window rect without accessibility permissions,
            // a common approach for soft blocking is showing it over the main screen or full screen.
            if let screen = NSScreen.main {
                overlayPanel.show(over: screen.frame)
            }
        } else {
            overlayPanel.hide()
        }
    }
}
