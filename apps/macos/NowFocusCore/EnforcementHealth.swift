import Foundation

public enum EnforcementStatus: String, Codable {
    case active
    case degraded
    case unavailable
}

public struct PlatformCapabilities: Codable {
    public let canBlockWebsites: Bool
    public let canBlockApplications: Bool
    public let isPrivilegedDaemonRunning: Bool
    
    public init(canBlockWebsites: Bool, canBlockApplications: Bool, isPrivilegedDaemonRunning: Bool) {
        self.canBlockWebsites = canBlockWebsites
        self.canBlockApplications = canBlockApplications
        self.isPrivilegedDaemonRunning = isPrivilegedDaemonRunning
    }
}
