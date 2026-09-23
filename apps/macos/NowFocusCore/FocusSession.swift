import Foundation

public enum FocusSessionStatus: String, Codable {
    case scheduled
    case active
    case completed
    case cancelled
    case expired
    case error
}

public enum EnforcementMode: String, Codable {
    case normal
    case strict
    case locked
}

public enum SessionType: String, Codable {
    case focus
    case bedtime_winddown
}

public enum NotificationMode: String, Codable {
    case normal
    case quiet
    case silent
}

public struct FocusSession: Codable, Identifiable {
    public let id: String
    public let policyId: String
    public let sessionType: SessionType
    
    public let startAt: Date
    public let endAt: Date
    
    public var status: FocusSessionStatus
    public let enforcementMode: EnforcementMode
    public let notificationMode: NotificationMode
    
    public let createdAt: Date
    public var completedAt: Date?
    public var cancelledAt: Date?
    
    public let deviceId: String
    public let revision: Int
    
    public init(id: String = UUID().uuidString,
                policyId: String,
                sessionType: SessionType = .focus,
                startAt: Date,
                endAt: Date,
                status: FocusSessionStatus = .active,
                enforcementMode: EnforcementMode = .normal,
                notificationMode: NotificationMode = .normal,
                createdAt: Date = Date(),
                completedAt: Date? = nil,
                cancelledAt: Date? = nil,
                deviceId: String,
                revision: Int = 1) {
        self.id = id
        self.policyId = policyId
        self.sessionType = sessionType
        self.startAt = startAt
        self.endAt = endAt
        self.status = status
        self.enforcementMode = enforcementMode
        self.notificationMode = notificationMode
        self.createdAt = createdAt
        self.completedAt = completedAt
        self.cancelledAt = cancelledAt
        self.deviceId = deviceId
        self.revision = revision
    }
}
