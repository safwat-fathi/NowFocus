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
}
