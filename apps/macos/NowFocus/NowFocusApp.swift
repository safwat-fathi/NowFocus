import SwiftUI
import NowFocusCore
import ServiceManagement

@main
struct NowFocusApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
    
    var body: some Scene {
        MenuBarExtra("NowFocus", systemImage: "clock") {
            MenuBarView()
        }
        
        Settings {
            PreferencesView()
        }
    }
}

class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        // Initialize DB
        _ = DatabaseManager.shared
        
        // Seed default policy on first launch
        DatabaseManager.shared.seedDefaultPolicyIfNeeded()
        
        // Register Daemon
        let daemonService = SMAppService.daemon(plistName: "com.getnowfocus.daemon.plist")
        do {
            try daemonService.register()
            print("Daemon registered successfully (or already registered)")
        } catch {
            print("Failed to register daemon: \(error)")
        }
        
        // Session Recovery
        recoverSession()
    }
    
    private func recoverSession() {
        do {
            guard var session = try DatabaseManager.shared.fetchActiveSession() else { return }
            
            let engine = SessionEngine()
            engine.evaluateState(for: &session)
            
            if engine.isActive(session) {
                // Session is still valid, let's fetch policy and enforce
                guard let policy = try DatabaseManager.shared.fetchPolicy(id: session.policyId) else { return }
                
                // Re-apply to daemon
                DaemonClient.shared.apply(policy: policy)
                
                // Re-apply app blocking
                let appNames = policy.applications.filter { $0.enabled }.map { $0.nativeIdentifier }
                AppBlocker.shared.updatePolicy(isSessionActive: true, blockedApps: appNames)
            } else {
                // Session expired while we were closed
                try DatabaseManager.shared.saveSession(session)
                DaemonClient.shared.clear()
                AppBlocker.shared.updatePolicy(isSessionActive: false, blockedApps: [])
            }
        } catch {
            print("Failed to recover session: \(error)")
        }
    }
}
