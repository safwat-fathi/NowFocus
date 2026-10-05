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
    /// Typed word for word and compared exactly, so the screen shows and checks this one string. The Arabic one
    /// uses only plain letters (no hamza or alef variants), so an exact match needs no normalization.
    public static var strictUnlockSentence: String {
        String(localized: "I am choosing to end this focus session early.", bundle: .nowFocusCore, locale: .nowFocusUI)
    }
    public static let strictUnlockPauseSeconds = 30

    public init() {}

    /// The one place exit friction is decided. Normal always allows it; Strict only once the
    /// type-the-sentence-then-wait flow has completed; Locked never does, whatever
    /// `unlockCompleted` says. A Strict session that has a voice note also needs it played through
    /// (`listened`); with no note (never recorded, mic denied, file gone) Strict is the plain
    /// unlock, so a missing file can't trap anyone. Same table as Android's `canCancel`.
    public static func canCancel(mode: EnforcementMode, unlockCompleted: Bool, hasVoiceNote: Bool = false, listened: Bool = false) -> Bool {
        switch mode {
        case .normal: return true
        case .strict: return unlockCompleted && (!hasVoiceNote || listened)
        case .locked: return false
        }
    }

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
