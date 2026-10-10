import Foundation
import NowFocusCore

class DaemonXPCDelegate: NSObject, NSXPCListenerDelegate, NowFocusDaemonProtocol {

    private let enforcer = NetworkEnforcer()
    private let commitmentStore = CommitmentStore()
    private let dnsEnforcer = DNSEnforcer()

    /// A kept (always-on) DNS is re-applied now, with no app running. Any other override left by a crash or a reboot
    /// is undone; a session that is still running is re-applied by the app when it relaunches (recoverSession).
    func restoreDNSOnLaunch() {
        do { try dnsEnforcer.onStart() } catch { print("DNS start-up failed: \(error)") }
    }

    func reassertDNS() { dnsEnforcer.reassert() }

    func keepDNS(jsonPayload: Data, withReply reply: @escaping (Bool, String?) -> Void) {
        do {
            let request = try JSONDecoder().decode(DNSKeepRequest.self, from: jsonPayload)
            try dnsEnforcer.setKeep(servers: request.servers)
            reply(true, nil)
        } catch {
            reply(false, "\(error)")
        }
    }

    func dnsStatus(withReply reply: @escaping (Data?) -> Void) {
        reply(dnsEnforcer.state.flatMap { try? JSONEncoder().encode($0) })
    }

    func applyDNS(jsonPayload: Data, withReply reply: @escaping (Bool, String?) -> Void) {
        do {
            let request = try JSONDecoder().decode(DNSApplyRequest.self, from: jsonPayload)
            try dnsEnforcer.apply(servers: request.servers)
            reply(true, nil)
        } catch {
            reply(false, "\(error)")
        }
    }

    func clearDNS(withReply reply: @escaping (Bool, String?) -> Void) {
        do {
            try dnsEnforcer.clear()
            reply(true, nil)
        } catch {
            reply(false, "\(error)")
        }
    }

    /// Called once at process startup — re-applies any commitment that
    /// survived a daemon restart (or clears one whose 14 days elapsed while
    /// the daemon wasn't running), so `/etc/hosts` reflects it even before any
    /// client connects.
    func restoreCommitmentOnLaunch() {
        commitmentStore.expireIfNeeded()
        if let state = commitmentStore.current {
            try? enforcer.applyCommitment(domains: state.domains)
        } else {
            // Covers the commitment-expired-while-the-daemon-was-down case:
            // expireIfNeeded() just cleared the JSON, but the hosts block it
            // wrote before going down is still sitting in /etc/hosts and
            // nothing else would ever remove it.
            try? enforcer.clearCommitment()
        }
    }

    /// Runs on a timer (see main.swift) so a commitment whose 14 days elapse
    /// while the daemon just keeps running — with no client ever calling
    /// `commitmentStatus` to trigger the check — still gets lifted from
    /// `/etc/hosts` without needing a restart or an app relaunch.
    func expireCommitmentIfNeeded() {
        let hadCommitment = commitmentStore.current != nil
        commitmentStore.expireIfNeeded()
        if hadCommitment && commitmentStore.current == nil {
            try? enforcer.clearCommitment()
        }
    }

    func listener(_ listener: NSXPCListener, shouldAcceptNewConnection newConnection: NSXPCConnection) -> Bool {
        // For the MVP without full code signing, we accept all connections.
        // In production, validate the connecting process via SecCodeCheckValidity
        // using the connection's audit token and a code signing requirement.
        
        newConnection.exportedInterface = NSXPCInterface(with: NowFocusDaemonProtocol.self)
        newConnection.exportedObject = self
        newConnection.resume()
        return true
    }
    
    func applyPolicy(jsonPayload: Data, withReply reply: @escaping (Bool, Error?) -> Void) {
        do {
            let policy = try JSONDecoder().decode(BlockPolicy.self, from: jsonPayload)
            try enforcer.apply(policy: policy)
            reply(true, nil)
        } catch {
            reply(false, error)
        }
    }
    
    func clearPolicy(withReply reply: @escaping (Bool, Error?) -> Void) {
        do {
            try enforcer.clear()
            reply(true, nil)
        } catch {
            reply(false, error)
        }
    }
    
    func ping(withReply reply: @escaping (Bool) -> Void) {
        reply(true)
    }

    func applyCommitment(jsonPayload: Data, withReply reply: @escaping (Bool, Error?) -> Void) {
        do {
            let request = try JSONDecoder().decode(CommitmentApplyRequest.self, from: jsonPayload)
            // Refuse to replace a commitment that's still in effect — decided
            // here in the daemon, not trusted to the (unauthenticated) caller.
            guard commitmentStore.apply(domains: request.domains) else {
                reply(false, NSError(
                    domain: "app.getnowfocus.daemon",
                    code: 1,
                    userInfo: [NSLocalizedDescriptionKey: "A commitment is already in effect and can't be replaced until it ends."]
                ))
                return
            }
            try enforcer.applyCommitment(domains: request.domains)
            reply(true, nil)
        } catch {
            reply(false, error)
        }
    }

    func clearCommitment(withReply reply: @escaping (Bool, String?) -> Void) {
        guard commitmentStore.clearIfInGrace() else {
            reply(false, "This commitment's cancellation window has passed — it runs the full 14 days.")
            return
        }
        do {
            try enforcer.clearCommitment()
            reply(true, nil)
        } catch {
            reply(false, "\(error)")
        }
    }

    func commitmentStatus(withReply reply: @escaping (Data?) -> Void) {
        commitmentStore.expireIfNeeded()
        guard let status = commitmentStore.status(),
              let data = try? JSONEncoder().encode(status) else {
            reply(nil)
            return
        }
        reply(data)
    }
}
