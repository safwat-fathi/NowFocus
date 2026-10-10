import Foundation
import NowFocusCore

/// Points the Mac's network services at the resolver the user chose in Settings while a session runs, and puts them
/// back. The choice, the validation and the reading of `networksetup`'s output are in NowFocusCore's `DNSSettings.swift`
/// (checked by scripts/core-checks.sh); this file only runs the tool and keeps the backup.
///
/// The backup (`dns_backup.json`, next to the commitment state, in a root-owned folder) is written before the first
/// change and never overwritten by a second `apply`, or the already-filtered DNS would become "the original". Services
/// that appear later are added the next time `apply` runs (the daemon re-applies every 120s while it holds a DNS). It is
/// only deleted after a successful restore, so a failed one is retried. A service whose DNS is not plain addresses (an
/// encrypted-DNS profile) is never touched.
///
/// `dns_state.json` (same folder) is the daemon's own memory of what it applied and whether the user keeps it on
/// outside sessions ("always on"). What to do at each event (`clear`, release, start) is decided by `DNSRules` in
/// NowFocusCore, checked by scripts/core-checks.sh; this file only runs the plan.
///
/// *** UNVERIFIED on a live session *** — the parsing is checked against real `networksetup` output, but the
/// `-setdnsservers` writes need the installed daemon and root; see docs/testing for the manual pass.
final class DNSEnforcer {
    enum DNSError: Error { case invalid, noService, tool(String) }

    private let backupURL: URL
    private let stateURL: URL
    private let queue = DispatchQueue(label: "app.getnowfocus.daemon.dns")

    init(directory: URL = URL(fileURLWithPath: "/Library/Application Support/NowFocus")) {
        backupURL = directory.appendingPathComponent("dns_backup.json")
        stateURL = directory.appendingPathComponent("dns_state.json")
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    /// The DNS the daemon last applied and whether it is kept on, or nil when it has changed nothing.
    var state: DNSState? {
        (try? Data(contentsOf: stateURL)).flatMap { try? JSONDecoder().decode(DNSState.self, from: $0) }
    }

    /// A session applies `servers`. Idempotent; the keep flag is whatever it already was.
    func apply(servers: [String]) throws {
        try applyState(DNSRules.afterSessionApply(state, servers))
    }

    /// The user keeps (`servers`) or stops keeping (nil) this DNS outside sessions.
    func setKeep(servers: [String]?) throws {
        if let servers {
            try applyState(DNSState(servers: servers, keep: true))
        } else {
            try run(DNSRules.onRelease(state))
        }
    }

    /// A session ended.
    func clear() throws { try run(DNSRules.onClear(state)) }

    /// The daemon is starting: re-apply a kept DNS, undo anything else left behind.
    func onStart() throws { try run(DNSRules.onStart(state)) }

    /// Every 120s while a DNS is held: pick up services that appeared (a USB-Ethernet dongle).
    /// ponytail: a 120s poll; subscribe to SCDynamicStore network changes if battery matters.
    func reassert() {
        guard let state else { return }
        do { try applyState(state) } catch { print("DNS re-assert failed: \(error)") }
    }

    private func run(_ plan: DNSPlan) throws {
        switch plan {
        case .nothing: return
        case .restore: try restore()
        case .apply(let servers): try applyState(DNSState(servers: servers, keep: true))
        }
    }

    private func applyState(_ state: DNSState) throws {
        // Any local process can reach the XPC service, so the addresses are checked here, not trusted.
        guard let ips = DNSResolvers.validate(state.servers) else { throw DNSError.invalid }
        try queue.sync {
            let current = try currentServices()
            guard !current.isEmpty else { throw DNSError.noService }
            var saved = readBackup()
            let known = Set(saved.map(\.service))
            let fresh = current.filter { !known.contains($0.service) }
            if !fresh.isEmpty {
                saved += fresh
                try writeBackup(saved)
            }
            // Written before the change, so a crash in between is retried by the re-assert, not forgotten.
            try JSONEncoder().encode(DNSState(servers: ips, keep: state.keep)).write(to: stateURL, options: .atomic)
            for service in current {
                try networksetup(["-setdnsservers", service.service] + ips)
            }
            flushCache()
        }
    }

    /// Idempotent: nothing to do when no backup exists. Also forgets the state, so the re-assert stops.
    private func restore() throws {
        try queue.sync {
            guard FileManager.default.fileExists(atPath: backupURL.path) else {
                try? FileManager.default.removeItem(at: stateURL)
                return
            }
            guard let data = try? Data(contentsOf: backupURL), let saved = try? JSONDecoder().decode([ServiceDNS].self, from: data) else {
                // Unreadable: nothing safe to write back. Drop it so a bad file can't wedge every later restore.
                try? FileManager.default.removeItem(at: backupURL)
                try? FileManager.default.removeItem(at: stateURL)
                throw DNSError.tool("the DNS backup was unreadable")
            }
            let present = Set(NetworkSetupOutput.services((try? networksetupOutput(["-listallnetworkservices"])) ?? ""))
            var failure: Error?
            for service in saved where present.contains(service.service) {
                do {
                    try networksetup(["-setdnsservers", service.service] + (service.servers.isEmpty ? ["Empty"] : service.servers))
                } catch {
                    failure = error
                }
            }
            if let failure { throw failure }
            try? FileManager.default.removeItem(at: backupURL)
            try? FileManager.default.removeItem(at: stateURL)
            flushCache()
        }
    }

    /// Every enabled service whose DNS is plain addresses (or automatic), with what it has now.
    private func currentServices() throws -> [ServiceDNS] {
        var out: [ServiceDNS] = []
        for name in NetworkSetupOutput.services(try networksetupOutput(["-listallnetworkservices"])) {
            guard let servers = NetworkSetupOutput.dnsServers((try? networksetupOutput(["-getdnsservers", name])) ?? "") else { continue }
            out.append(ServiceDNS(service: name, servers: servers))
        }
        return out
    }

    private func readBackup() -> [ServiceDNS] {
        (try? Data(contentsOf: backupURL)).flatMap { try? JSONDecoder().decode([ServiceDNS].self, from: $0) } ?? []
    }

    private func writeBackup(_ services: [ServiceDNS]) throws {
        try JSONEncoder().encode(services).write(to: backupURL, options: .atomic)
    }

    private func networksetup(_ arguments: [String]) throws {
        _ = try networksetupOutput(arguments)
    }

    /// Arguments go straight to the tool, never through a shell, so a service name or an address cannot inject anything.
    private func networksetupOutput(_ arguments: [String]) throws -> String {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/sbin/networksetup")
        process.arguments = arguments
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = pipe
        try process.run()
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        let text = String(decoding: data, as: UTF8.self)
        guard process.terminationStatus == 0 else { throw DNSError.tool(text.trimmingCharacters(in: .whitespacesAndNewlines)) }
        return text
    }

    private func flushCache() {
        for (path, args) in [("/usr/bin/dscacheutil", ["-flushcache"]), ("/usr/bin/killall", ["-HUP", "mDNSResponder"])] {
            let process = Process()
            process.executableURL = URL(fileURLWithPath: path)
            process.arguments = args
            try? process.run()
        }
    }
}
