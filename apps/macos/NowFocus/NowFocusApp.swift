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
