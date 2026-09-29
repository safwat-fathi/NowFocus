import Foundation
import GRDB

// MARK: - Goal

public enum GoalPriority: String, Codable, CaseIterable {
    case high, medium, low

    public var label: String {
        switch self {
        case .high:   return "High"
        case .medium: return "Med"
        case .low:    return "Low"
        }
    }
}

public struct UserGoal: Codable, Identifiable {
    public let id: String
    public var text: String
    public var priority: GoalPriority
    public let createdAt: Date
    public var updatedAt: Date

    public init(
        id: String = UUID().uuidString,
        text: String,
        priority: GoalPriority = .high,
        createdAt: Date = Date(),
        updatedAt: Date = Date()
    ) {
        self.id = id
        self.text = text
        self.priority = priority
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }
}

extension UserGoal: FetchableRecord, PersistableRecord {
    public static let databaseTableName = "userGoal"
}

// MARK: - Connection

public struct UserConnection: Codable, Identifiable {
    public let id: String
    public var name: String
    public var phoneNumber: String?
    public let createdAt: Date
    public var updatedAt: Date

    public init(
        id: String = UUID().uuidString,
        name: String,
        phoneNumber: String? = nil,
        createdAt: Date = Date(),
        updatedAt: Date = Date()
    ) {
        self.id = id
        self.name = name
        self.phoneNumber = phoneNumber
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }
}

extension UserConnection: FetchableRecord, PersistableRecord {
    public static let databaseTableName = "userConnection"
}
