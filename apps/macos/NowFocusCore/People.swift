import Foundation

private let dayInterval: TimeInterval = 24 * 60 * 60

/// Someone you'd rather hear from than scroll. `lastTalkedAt` is what you told the app plus taps on
/// the block screen's Call/Text: not call or message history, and a tap that never becomes a call
/// still counts. Nil means "can't remember". Rows saved before this existed may have no number;
/// they stay listed but are never picked.
public struct UserConnection: Codable, Identifiable {
    public let id: String
    public var name: String
    public var phoneNumber: String?
    public let createdAt: Date
    public var updatedAt: Date
    public var lastTalkedAt: Date?

    public init(
        id: String = UUID().uuidString,
        name: String,
        phoneNumber: String? = nil,
        createdAt: Date = Date(),
        updatedAt: Date = Date(),
        lastTalkedAt: Date? = nil
    ) {
        self.id = id
        self.name = name
        self.phoneNumber = phoneNumber
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.lastTalkedAt = lastTalkedAt
    }

    /// "3 days" / "3 weeks" / "2 months", or nil when unknown or too recent to claim anything.
    public func sinceLabel(now: Date) -> String? {
        guard let lastTalkedAt else { return nil }
        let days = Int(now.timeIntervalSince(lastTalkedAt) / dayInterval)
        switch days {
        case ..<3:  return nil
        case ..<14: return String(localized: "\(days) days", bundle: .nowFocusCore, locale: .nowFocusUI)
        case ..<60: return String(localized: "\(days / 7) weeks", bundle: .nowFocusCore, locale: .nowFocusUI)
        default:    return String(localized: "\(days / 30) months", bundle: .nowFocusCore, locale: .nowFocusUI)
        }
    }

    var reachablePhone: String? {
        guard let phone = phoneNumber?.trimmingCharacters(in: .whitespaces), !phone.isEmpty else { return nil }
        return phone
    }
}

public enum PeopleRotation {
    public static let max = 5

    /// Whoever you talked to longest ago among people with a number; "can't remember" counts as
    /// oldest and ties go to whoever was added first.
    public static func next(_ people: [UserConnection]) -> UserConnection? {
        people.filter { $0.reachablePhone != nil }.min { a, b in
            let l = (a.lastTalkedAt ?? .distantPast, a.createdAt)
            let r = (b.lastTalkedAt ?? .distantPast, b.createdAt)
            return l < r
        }
    }

    /// New people need a number, are capped at `max`, and can't repeat a number already listed.
    public static func canAdd(phone: String, to existing: [UserConnection]) -> Bool {
        let phone = phone.trimmingCharacters(in: .whitespaces)
        return !phone.isEmpty && existing.count < max && !existing.contains { $0.reachablePhone == phone }
    }
}

/// The "Last talked" chips: (label, how many days ago that stands for). Same as Android.
public enum LastTalked {
    public static let choices: [(label: String, daysAgo: Int?)] = [
        (String(localized: "This week", bundle: .nowFocusCore, locale: .nowFocusUI), 3),
        (String(localized: "About 2 weeks", bundle: .nowFocusCore, locale: .nowFocusUI), 14),
        (String(localized: "About a month", bundle: .nowFocusCore, locale: .nowFocusUI), 30),
        (String(localized: "Longer", bundle: .nowFocusCore, locale: .nowFocusUI), 90),
        (String(localized: "Can't remember", bundle: .nowFocusCore, locale: .nowFocusUI), nil),
    ]

    /// The chip whose day count is nearest, so a stored date still highlights a chip as it ages.
    public static func choice(for lastTalkedAt: Date?, now: Date) -> Int? {
        guard let lastTalkedAt else { return nil }
        let days = now.timeIntervalSince(lastTalkedAt) / dayInterval
        switch days {
        case ..<9:  return 3
        case ..<22: return 14
        case ..<60: return 30
        default:    return 90
        }
    }

    public static func date(daysAgo: Int?, now: Date) -> Date? {
        daysAgo.map { now.addingTimeInterval(-TimeInterval($0) * dayInterval) }
    }
}

/// tel:/sms: links for the block screen's Call and Text. Whatever handles them (FaceTime, Messages)
/// is up to the machine, so the number is always shown too.
public enum PhoneLink {
    /// Digits plus a leading `+`, `*` and `#`; spaces, dots, dashes and brackets are dropped. Nil with no digits.
    public static func dialString(_ phone: String) -> String? {
        let kept = phone.filter { $0.isNumber || $0 == "+" || $0 == "*" || $0 == "#" }
        return kept.contains(where: { $0.isNumber }) ? kept : nil
    }

    public static func url(scheme: String, phone: String) -> URL? {
        dialString(phone).flatMap { URL(string: "\(scheme):\($0)") }
    }
}
