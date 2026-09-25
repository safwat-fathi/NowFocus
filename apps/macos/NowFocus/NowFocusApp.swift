import SwiftUI
import NowFocusCore

@main
struct NowFocusApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
    
    var body: some Scene {
        MenuBarExtra("NowFocus", systemImage: "clock") {
            MenuBarView()
        }
        .menuBarExtraStyle(.window)

        Settings {
            PreferencesView()
        }
    }
}

class AppDelegate: NSObject, NSApplicationDelegate {
    private var sessionMonitor: Timer?

    func applicationDidFinishLaunching(_ notification: Notification) {
        // Initialize DB
        _ = DatabaseManager.shared

        // Seed default policy on first launch
        DatabaseManager.shared.seedDefaultPolicyIfNeeded()

        // Register Daemon
        DaemonRegistrationStatus.shared.refresh()

        // Session Recovery
        recoverSession()

        // Keep enforcement in sync with session expiry even if the menu is
        // never reopened — see SessionController's doc comment.
        sessionMonitor = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            self?.checkSessionExpiry()
        }

        // Preferences temporarily promotes us to a regular app (Dock/Cmd+Tab)
        // so its window is reachable; drop back to accessory once it closes.
        // willClose fires while the closing window is still visible, so skip it;
        // only titled windows count (overlay panels and the menu bar popover aren't).
        NotificationCenter.default.addObserver(forName: NSWindow.willCloseNotification, object: nil, queue: .main) { note in
            let closing = note.object as? NSWindow
            let otherTitled = NSApp.windows.contains {
                $0 !== closing && $0.isVisible && $0.styleMask.contains(.titled)
            }
            if !otherTitled {
                NSApp.setActivationPolicy(.accessory)
            }
        }
    }

    // Refuse user-initiated quits (menu Quit, Cmd+Q, Dock) while a session is
    // active — quitting would kill AppBlocker. Force Quit can't be stopped;
    // recoverSession() re-applies enforcement on next launch.
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        // Logout/restart/shutdown carry a quit reason; never block those.
        if NSAppleEventManager.shared().currentAppleEvent?
            .attributeDescriptor(forKeyword: kAEQuitReason) != nil {
            return .terminateNow
        }
        guard var session = try? DatabaseManager.shared.fetchActiveSession() else { return .terminateNow }
        let engine = SessionEngine()
        engine.evaluateState(for: &session)
        return engine.isActive(session) ? .terminateCancel : .terminateNow
    }

    private func recoverSession() {
        do {
            guard var session = try DatabaseManager.shared.fetchActiveSession() else {
                // No active session on record. Clear enforcement unconditionally:
                // if a prior session ended while the app was closed, this is what
                // unsticks a /etc/hosts block or overlay left over from it.
                SessionController.stopEnforcement()
                return
            }

            let engine = SessionEngine()
            engine.evaluateState(for: &session)

            if engine.isActive(session) {
                guard let policy = try DatabaseManager.shared.fetchPolicy(id: session.policyId) else { return }
                SessionController.startEnforcement(policy: policy)
            } else {
                // Session expired while we were closed.
                SessionController.endSession(session)
            }
        } catch {
            print("Failed to recover session: \(error)")
        }
    }

    private func checkSessionExpiry() {
        do {
            guard var session = try DatabaseManager.shared.fetchActiveSession() else { return }
            let engine = SessionEngine()
            engine.evaluateState(for: &session)
            if !engine.isActive(session) {
                SessionController.endSession(session)
            }
        } catch {
            print("Failed to check session expiry: \(error)")
        }
    }
}
