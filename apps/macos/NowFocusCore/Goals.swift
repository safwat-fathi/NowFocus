import Foundation

public enum GoalPriority: String, Codable, CaseIterable {
    case high, medium, low

    public var label: String {
        switch self {
        case .high:   return String(localized: "High", bundle: .nowFocusCore, locale: .nowFocusUI)
        case .medium: return String(localized: "Med", bundle: .nowFocusCore, locale: .nowFocusUI)
        case .low:    return String(localized: "Low", bundle: .nowFocusCore, locale: .nowFocusUI)
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

public enum Goals {
    /// A random high-priority goal, falling back to any goal. Same rule as Android's `Goals.pick`.
    public static func pick<G: RandomNumberGenerator>(_ goals: [UserGoal], using generator: inout G) -> UserGoal? {
        let high = goals.filter { $0.priority == .high }
        return (high.isEmpty ? goals : high).randomElement(using: &generator)
    }

    public static func pick(_ goals: [UserGoal]) -> UserGoal? {
        var generator = SystemRandomNumberGenerator()
        return pick(goals, using: &generator)
    }
}
