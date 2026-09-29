import Foundation
import NowFocusCore

/// Owns `/etc/hosts` for two independent layers — a focus session's policy
/// and the always-on Commitment Shield. Each layer's method reads the file
/// fresh, touches only its *own* marker block, and writes back — it never
/// holds the other layer's domains in memory. That statelessness matters
/// across a daemon restart: if it held state, restoring a commitment after a
/// restart (see DaemonXPCDelegate.restoreCommitmentOnLaunch) would rewrite the
/// whole file from memory and silently drop an existing session block that
/// this process never loaded. Before this class existed at all, `apply()`/
/// `clear()` were the only two operations and shared one block — so ending a
/// session also wiped the commitment; this fixes that without reintroducing
/// the state-loss risk a shared in-memory model would bring back.
class NetworkEnforcer {
    private let hostsFilePath = "/etc/hosts"
    private let backupHostsFilePath = "/etc/hosts.focus.backup"

    func apply(policy: BlockPolicy) throws {
        try rewriteBlock(start: HostsFileMarkers.sessionStart, end: HostsFileMarkers.sessionEnd, rules: policy.domains)
    }

    func clear() throws {
        try rewriteBlock(start: HostsFileMarkers.sessionStart, end: HostsFileMarkers.sessionEnd, rules: [])
    }

    func applyCommitment(domains: [String]) throws {
        let rules = domains.map { DomainRule(domain: $0, includeSubdomains: true) }
        try rewriteBlock(start: HostsFileMarkers.commitmentStart, end: HostsFileMarkers.commitmentEnd, rules: rules)
    }

    func clearCommitment() throws {
        try rewriteBlock(start: HostsFileMarkers.commitmentStart, end: HostsFileMarkers.commitmentEnd, rules: [])
    }

    private func rewriteBlock(start: String, end: String, rules: [DomainRule]) throws {
        var hostsContent = try readHosts()
        hostsContent = removeBlock(from: hostsContent, start: start, end: end)
        if !rules.isEmpty {
            hostsContent += "\n" + blockContent(start: start, end: end, rules: rules)
        }
        try writeHosts(content: hostsContent)
        flushDNSCache()
    }

    private func blockContent(start: String, end: String, rules: [DomainRule]) -> String {
        var block = "\(start)\n"
        for rule in rules where rule.enabled {
            // Trust boundary: the XPC caller isn't verified (see
            // DaemonXPCDelegate), so re-validate here even though the UI
            // already does. This writes to /etc/hosts as root.
            guard let domain = DomainValidation.normalize(rule.domain) else {
                print("Skipping invalid domain: \(rule.domain)")
                continue
            }
            block += "127.0.0.1 \(domain)\n"
            if rule.includeSubdomains {
                block += "127.0.0.1 www.\(domain)\n"
                block += "127.0.0.1 m.\(domain)\n"
                block += "127.0.0.1 mobile.\(domain)\n"
            }
        }
        block += "\(end)\n"
        return block
    }

    private func readHosts() throws -> String {
        return try String(contentsOfFile: hostsFilePath, encoding: .utf8)
    }

    private func writeHosts(content: String) throws {
        // Backup first if not exists
        if !FileManager.default.fileExists(atPath: backupHostsFilePath) {
            try FileManager.default.copyItem(atPath: hostsFilePath, toPath: backupHostsFilePath)
        }

        try content.write(toFile: hostsFilePath, atomically: true, encoding: .utf8)
    }

    private func removeBlock(from content: String, start: String, end: String) -> String {
        var lines = content.components(separatedBy: .newlines)
        var isInsideBlock = false
        var resultLines: [String] = []

        for line in lines {
            if line == start {
                isInsideBlock = true
                continue
            }
            if line == end {
                isInsideBlock = false
                continue
            }
            if !isInsideBlock {
                resultLines.append(line)
            }
        }

        return resultLines.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func flushDNSCache() {
        // Run killall -HUP mDNSResponder to flush cache
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/killall")
        process.arguments = ["-HUP", "mDNSResponder"]
        do {
            try process.run()
        } catch {
            print("Failed to flush DNS cache with killall -HUP: \(error)")
        }

        let dscacheutil = Process()
        dscacheutil.executableURL = URL(fileURLWithPath: "/usr/bin/dscacheutil")
        dscacheutil.arguments = ["-flushcache"]
        do {
            try dscacheutil.run()
        } catch {
            print("Failed to flush DNS cache with dscacheutil: \(error)")
        }
    }

    #if DEBUG
    /// Pure in-memory check — never touches the real /etc/hosts. Mirrors the
    /// exact sequence a daemon restart with a lingering session block plus a
    /// live commitment goes through: apply session, apply commitment, clear
    /// commitment — asserting the session block survives every step.
    static func runSelfCheck() {
        let enforcer = NetworkEnforcer()
        let baseline = "127.0.0.1 localhost\n255.255.255.255 broadcasthost\n::1 localhost\n"

        var content = enforcer.removeBlock(from: baseline, start: HostsFileMarkers.sessionStart, end: HostsFileMarkers.sessionEnd)
        content += "\n" + enforcer.blockContent(start: HostsFileMarkers.sessionStart, end: HostsFileMarkers.sessionEnd, rules: [DomainRule(domain: "example.com", includeSubdomains: false)])

        content = enforcer.removeBlock(from: content, start: HostsFileMarkers.commitmentStart, end: HostsFileMarkers.commitmentEnd)
        content += "\n" + enforcer.blockContent(start: HostsFileMarkers.commitmentStart, end: HostsFileMarkers.commitmentEnd, rules: [DomainRule(domain: "reddit.com", includeSubdomains: false)])
        assert(content.contains("example.com"), "applying the commitment must not disturb the session block")

        content = enforcer.removeBlock(from: content, start: HostsFileMarkers.commitmentStart, end: HostsFileMarkers.commitmentEnd)
        assert(content.contains("example.com"), "clearing the commitment must not disturb the session block")
        assert(!content.contains("reddit.com"), "clearing the commitment must actually remove its own block")
        assert(content.contains("localhost"), "pre-existing hosts lines must survive throughout")

        print("NetworkEnforcer self-check passed")
    }
    #endif
}
