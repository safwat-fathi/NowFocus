import Foundation
import NowFocusCore

/// The app's side of "DNS during a session" and "always on". The user's intent lives here (UserDefaults); the daemon
/// owns what is actually applied and remembers it across reboots. Every change goes through `apply`, and the app
/// re-states its intent at launch (`reconcile`), because the daemon can only remember, not know what the user wants now.
@MainActor
enum DNSController {
    /// Saves `choice` and pushes it to the daemon.
    static func apply(_ choice: DNSChoice) {
        choice.save()
        push(choice)
    }

    /// At launch and when the DNS screen appears: the daemon should match the stored choice.
    static func reconcile() {
        push(DNSChoice.load())
    }

    private static func push(_ choice: DNSChoice) {
        let servers = choice.servers
        if choice.alwaysOn && !servers.isEmpty {
            DaemonClient.shared.keepDNS(servers: servers)
        } else {
            // Stop keeping (a no-op unless it was kept), then settle the session: a live session gets the pick
            // (or, with the system DNS chosen, its servers back); with none running there is nothing to apply.
            DaemonClient.shared.keepDNS(servers: nil)
            if SessionController.status.isActive {
                if servers.isEmpty { DaemonClient.shared.clearDNSAsync() } else { DaemonClient.shared.applyDNS(servers: servers) }
            }
        }
    }
}
