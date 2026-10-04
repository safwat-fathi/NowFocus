import AppKit
import SystemConfiguration
import NowFocusCore

extension SyncController {
    /// The app's one account/sync controller. Creating it starts nothing: `start()` (called at launch) only begins
    /// syncing, and only touches the network, if a sign-in is already stored.
    @MainActor
    static let shared = SyncController(userAgent: "NowFocus-macos/\(appVersion)", deviceName: computerName)

    private static var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
    }

    /// What this Mac is called in System Settings. `Host.current().localizedName` can block on name resolution.
    private static var computerName: String {
        (SCDynamicStoreCopyComputerName(nil, nil) as String?) ?? ProcessInfo.processInfo.hostName
    }
}

/// Wires sync into the app's lifecycle (called once, at launch).
@MainActor
enum AppSync {
    static func start() {
        SyncController.shared.start()

        // Sync changed profiles or bedtime underneath the running app.
        NotificationCenter.default.addObserver(forName: .nowFocusSyncApplied, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated {
                // Read the (possibly new) bedtime now rather than at the next 30 s tick.
                BedtimeScheduler.shared.start()
                // A running session keeps its rules; this only adds what another device added (sync never removes
                // a block from a profile a session is using).
                if let session = try? DatabaseManager.shared.fetchActiveSession(), SessionEngine().isActive(session),
                   let policy = try? DatabaseManager.shared.fetchPolicy(id: session.policyId) {
                    SessionController.startEnforcement(policy: policy, sessionId: session.id, endAt: session.endAt, sessionType: session.sessionType)
                }
            }
        }

        NotificationCenter.default.addObserver(forName: NSApplication.didBecomeActiveNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { SyncController.shared.syncNow() }
        }
    }
}
