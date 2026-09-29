import Foundation

@objc public protocol NowFocusDaemonProtocol {
    /// Applies a block policy in the LaunchDaemon (e.g., sinkholing via /etc/hosts)
    /// - Parameters:
    ///   - jsonPayload: A JSON encoded representation of the `BlockPolicy` struct.
    ///   - reply: A callback to return success/failure.
    func applyPolicy(jsonPayload: Data, withReply reply: @escaping (Bool, Error?) -> Void)

    /// Removes the current block policy in the LaunchDaemon
    /// - Parameter reply: A callback to return success/failure.
    func clearPolicy(withReply reply: @escaping (Bool, Error?) -> Void)

    /// Checks the health and status of the daemon
    /// - Parameter reply: A callback returning true if the daemon is responsive
    func ping(withReply reply: @escaping (Bool) -> Void)

    /// Starts (or replaces) the 24/7 Always-Blocked Commitment Shield. The
    /// daemon — not the app — owns its timing and persistence, so the lock
    /// survives the menu-bar app quitting; see `CommitmentApplyRequest`.
    /// - Parameters:
    ///   - jsonPayload: A JSON-encoded `CommitmentApplyRequest`.
    ///   - reply: Success/failure.
    func applyCommitment(jsonPayload: Data, withReply reply: @escaping (Bool, Error?) -> Void)

    /// Cancels the active commitment. Only honored inside its irrevocable grace
    /// window (see `CommitmentStatusDTO.canCancelNow`) — the daemon refuses
    /// (`success: false`, a message) once the window has passed, independent of
    /// whether the app that created it is even still running.
    /// - Parameter reply: (success, refusal message if not cancelled).
    func clearCommitment(withReply reply: @escaping (Bool, String?) -> Void)

    /// - Parameter reply: A JSON-encoded `CommitmentStatusDTO`, or nil if no commitment is active.
    func commitmentStatus(withReply reply: @escaping (Data?) -> Void)
}

/// Request payload for `applyCommitment` — domains only. The daemon stamps its
/// own creation time/uptime/boot-time on receipt; a client-supplied end date
/// would defeat the anti-tamper point of the whole feature.
public struct CommitmentApplyRequest: Codable {
    public let domains: [String]

    public init(domains: [String]) {
        self.domains = domains
    }
}

/// Shared between `NetworkEnforcer` (which writes these) and the app's
/// Devices screen (which reads `/etc/hosts` — world-readable, no privilege
/// needed — to check whether a block it expects is actually present, rather
/// than just trusting the daemon ping).
public enum HostsFileMarkers {
    public static let sessionStart = "### FOCUS APP BLOCK START ###"
    public static let sessionEnd = "### FOCUS APP BLOCK END ###"
    public static let commitmentStart = "### FOCUS COMMITMENT BLOCK START ###"
    public static let commitmentEnd = "### FOCUS COMMITMENT BLOCK END ###"
}

/// What the UI is allowed to know about the active commitment — never the raw
/// boot-time/uptime anchors that make it tamper-resistant.
public struct CommitmentStatusDTO: Codable {
    public let domains: [String]
    public let endAt: Date
    public let canCancelNow: Bool
    public let remainingSeconds: TimeInterval

    public init(domains: [String], endAt: Date, canCancelNow: Bool, remainingSeconds: TimeInterval) {
        self.domains = domains
        self.endAt = endAt
        self.canCancelNow = canCancelNow
        self.remainingSeconds = remainingSeconds
    }
}
