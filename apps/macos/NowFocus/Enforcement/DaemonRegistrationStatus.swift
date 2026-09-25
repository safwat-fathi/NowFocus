import Foundation
import ServiceManagement

/// Tracks whether the privileged helper daemon is actually registered, so the
/// UI can tell the user when website blocking is silently unavailable instead
/// of just printing to a console nobody reads.
final class DaemonRegistrationStatus {
    static let shared = DaemonRegistrationStatus()
    private init() {}

    enum State {
        case unknown
        case registered
        case requiresApproval
        case failed(String)
    }

    private(set) var state: State = .unknown

    @discardableResult
    func refresh() -> State {
        let service = SMAppService.daemon(plistName: "com.getnowfocus.daemon.plist")

        switch service.status {
        case .enabled:
            state = .registered
        case .requiresApproval:
            state = .requiresApproval
        case .notRegistered, .notFound:
            do {
                try service.register()
                state = service.status == .requiresApproval ? .requiresApproval : .registered
            } catch {
                let nsError = error as NSError
                print("Daemon registration failed: \(error.localizedDescription) (domain: \(nsError.domain), code: \(nsError.code))")
                state = .failed(error.localizedDescription)
            }
        @unknown default:
            state = .unknown
        }

        return state
    }
}
