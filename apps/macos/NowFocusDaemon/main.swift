import Foundation

#if DEBUG
NetworkEnforcer.runSelfCheck()
#endif

let delegate = DaemonXPCDelegate()
delegate.restoreCommitmentOnLaunch()
delegate.restoreDNSOnLaunch()

let listener = NSXPCListener(machServiceName: "app.getnowfocus.daemon")
listener.delegate = delegate
listener.resume()

// Catches a Commitment Shield's 14-day expiry even if no client ever calls
// commitmentStatus (e.g. the menu-bar app is never reopened) — hourly is
// frequent enough for a 14-day window without waking the daemon needlessly.
let commitmentExpiryTimer = Timer.scheduledTimer(withTimeInterval: 3600, repeats: true) { _ in
    delegate.expireCommitmentIfNeeded()
}
RunLoop.main.add(commitmentExpiryTimer, forMode: .common)

// While a DNS is held (a session, or always on), notice network services that came up since.
let dnsReassertTimer = Timer.scheduledTimer(withTimeInterval: 120, repeats: true) { _ in
    delegate.reassertDNS()
}
RunLoop.main.add(dnsReassertTimer, forMode: .common)

// Keep the daemon running
RunLoop.main.run()
