import ActivityKit
import Foundation

/// The Live Activity shown on the Lock Screen / Dynamic Island while a session runs: iOS's
/// counterpart to Android's ongoing notification. Shared by the app and the widget extension.
struct SessionActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var endAt: Date
        var mode: String
    }
    var policyName: String
    var startAt: Date
}
