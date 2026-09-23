import Foundation

public class SessionEngine {
    
    public init() {}
    
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
