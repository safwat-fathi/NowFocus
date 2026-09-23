import Foundation

// `swift` ignores top-level code in any file but the first when given multiple
// files, so concatenate before running:
// cat apps/macos/NowFocusCore/FocusSession.swift apps/macos/NowFocusCore/SessionEngine.swift test_session_engine.swift > /tmp/t.swift && swift /tmp/t.swift

func assertEqual<T: Equatable>(_ a: T, _ b: T, _ msg: String, line: Int = #line) {
    guard a == b else {
        print("FAIL (line \(line)): \(msg) — got \(a), expected \(b)")
        exit(1)
    }
}

let engine = SessionEngine()
let now = Date()

// Active session past its endAt should transition to .completed.
var expired = FocusSession(
    policyId: "p1",
    startAt: now.addingTimeInterval(-3600),
    endAt: now.addingTimeInterval(-1),
    status: .active,
    deviceId: "test"
)
engine.evaluateState(for: &expired, currentTime: now)
assertEqual(expired.status, .completed, "active session past endAt should become completed")
assertEqual(engine.isActive(expired, currentTime: now), false, "expired session should not be reported active")

// Active session still within its window should remain active.
var stillActive = FocusSession(
    policyId: "p1",
    startAt: now.addingTimeInterval(-60),
    endAt: now.addingTimeInterval(60),
    status: .active,
    deviceId: "test"
)
engine.evaluateState(for: &stillActive, currentTime: now)
assertEqual(stillActive.status, .active, "session within window should remain active")
assertEqual(engine.isActive(stillActive, currentTime: now), true, "session within window should be reported active")

print("SessionEngine: all checks passed")
