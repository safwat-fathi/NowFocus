import Foundation

/// What happens when the user tries to end a session early, decided by its
/// `enforcementMode` alone — lives here (not the view) so the popover and any
/// future surface that can end a session gate the same way.
public enum SessionStopGate {
    case immediate
    case requiresUnlock(sentence: String, pauseSeconds: Int)
    case locked
}

public class SessionEngine {
    /// Matches the design's own unlock copy/timing exactly (PC prototype's
    /// `SENT` constant and 30s default `unlockWait`), not reinvented here.
    public static let strictUnlockSentence = "I am choosing to end this focus session early."
    public static let strictUnlockPauseSeconds = 30

    public init() {}

    public func stopGate(for session: FocusSession) -> SessionStopGate {
        switch session.enforcementMode {
        case .normal:
            return .immediate
        case .strict:
            return .requiresUnlock(sentence: Self.strictUnlockSentence, pauseSeconds: Self.strictUnlockPauseSeconds)
        case .locked:
            return .locked
        }
    }
    
    public func evaluateState(for session: inout FocusSession, currentTime: Date = Date()) {
        switch session.status {
        case .scheduled:
            if currentTime >= session.startAt && currentTime < session.endAt {
                session.status = .active
            } else if currentTime >= session.endAt {
                session.status = .expired
            }
        case .active:
            if currentTime >= session.endAt {
                session.status = .completed
            }
        default:
            break
        }
    }
    
    public func isActive(_ session: FocusSession, currentTime: Date = Date()) -> Bool {
        return session.status == .active && currentTime >= session.startAt && currentTime < session.endAt
    }
}
