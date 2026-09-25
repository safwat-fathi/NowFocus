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

/// Identifies the origin of a session or policy — whether it was created
/// directly by the user, programmatically by an authorized extension, or
/// automatically by a recurring schedule.
public enum SessionSource: String, Codable {
    case user
    case extensionAPI = "extension"
    case schedule
}

/// Metadata about the extension that created a resource, used for
/// UI attribution (e.g., "🕌 Dhuhr Prayer · via Salah Extension").
public struct ExtensionMeta: Codable {
    public let extensionId: String
    public let extensionName: String
    public let extensionIconUrl: String?

    public init(extensionId: String,
                extensionName: String,
                extensionIconUrl: String? = nil) {
        self.extensionId = extensionId
        self.extensionName = extensionName
        self.extensionIconUrl = extensionIconUrl
    }
}

public struct FocusSession: Codable, Identifiable {
    public let id: String
    public let policyId: String
    public let sessionType: SessionType

    /// Where this session originated — user action, extension API, or schedule.
    public let source: SessionSource
    /// The extension that created this session, if `source == .extensionAPI`.
    public let createdByExtensionId: String?
    /// Display metadata for the creating extension (name, icon) for UI attribution.
    public let extensionMetadata: ExtensionMeta?

    public let startAt: Date
    public let endAt: Date
    
    public var status: FocusSessionStatus
    public let enforcementMode: EnforcementMode
    public let notificationMode: NotificationMode
    
    public let createdAt: Date
    public var completedAt: Date?
    public var cancelledAt: Date?
    /// Timestamp when the session was paused, if applicable.
    public var pausedAt: Date?
    
    public let deviceId: String
    public let revision: Int
    
    public init(id: String = UUID().uuidString,
                policyId: String,
                sessionType: SessionType = .focus,
                source: SessionSource = .user,
                createdByExtensionId: String? = nil,
                extensionMetadata: ExtensionMeta? = nil,
                startAt: Date,
                endAt: Date,
                status: FocusSessionStatus = .active,
                enforcementMode: EnforcementMode = .normal,
                notificationMode: NotificationMode = .normal,
                createdAt: Date = Date(),
                completedAt: Date? = nil,
                cancelledAt: Date? = nil,
                pausedAt: Date? = nil,
                deviceId: String,
                revision: Int = 1) {
        self.id = id
        self.policyId = policyId
        self.sessionType = sessionType
        self.source = source
        self.createdByExtensionId = createdByExtensionId
        self.extensionMetadata = extensionMetadata
        self.startAt = startAt
        self.endAt = endAt
        self.status = status
        self.enforcementMode = enforcementMode
        self.notificationMode = notificationMode
        self.createdAt = createdAt
        self.completedAt = completedAt
        self.cancelledAt = cancelledAt
        self.pausedAt = pausedAt
        self.deviceId = deviceId
        self.revision = revision
    }
}
