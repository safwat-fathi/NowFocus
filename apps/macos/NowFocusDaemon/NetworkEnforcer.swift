import Foundation
import NowFocusCore

class NetworkEnforcer {
    private let hostsFilePath = "/etc/hosts"
    private let backupHostsFilePath = "/etc/hosts.focus.backup"
    private let startMarker = "### FOCUS APP BLOCK START ###"
    private let endMarker = "### FOCUS APP BLOCK END ###"
    
    func apply(policy: BlockPolicy) throws {
        var hostsContent = try readHosts()
        
        // Remove existing block block
        hostsContent = removeFocusBlock(from: hostsContent)
        
        // Generate new block block
        if !policy.domains.isEmpty {
            var blockContent = "\(startMarker)\n"
            for rule in policy.domains where rule.enabled {
                blockContent += "127.0.0.1 \(rule.domain)\n"
                if rule.includeSubdomains {
                    blockContent += "127.0.0.1 www.\(rule.domain)\n"
                    blockContent += "127.0.0.1 m.\(rule.domain)\n"
                    blockContent += "127.0.0.1 mobile.\(rule.domain)\n"
                }
            }
            blockContent += "\(endMarker)\n"
            hostsContent += "\n" + blockContent
        }
        
        try writeHosts(content: hostsContent)
        flushDNSCache()
    }
    
    func clear() throws {
        let hostsContent = try readHosts()
        let clearedContent = removeFocusBlock(from: hostsContent)
        try writeHosts(content: clearedContent)
        flushDNSCache()
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
    
    private func removeFocusBlock(from content: String) -> String {
        var lines = content.components(separatedBy: .newlines)
        var isInsideBlock = false
        var resultLines: [String] = []
        
        for line in lines {
            if line == startMarker {
                isInsideBlock = true
                continue
            }
            if line == endMarker {
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
}
