import Foundation
import NowFocusCore

public class DaemonClient {
    public static let shared = DaemonClient()
    
    private var connection: NSXPCConnection?
    
    private init() {
        setupConnection()
    }
    
    private func setupConnection() {
        let newConnection = NSXPCConnection(machServiceName: "app.getnowfocus.daemon", options: .privileged)
        newConnection.remoteObjectInterface = NSXPCInterface(with: NowFocusDaemonProtocol.self)
        
        newConnection.interruptionHandler = { [weak self] in
            print("Daemon connection interrupted")
            self?.connection = nil
        }
        
        newConnection.invalidationHandler = { [weak self] in
            print("Daemon connection invalidated")
            self?.connection = nil
        }
        
        newConnection.resume()
        self.connection = newConnection
    }
    
    /// Builds a fresh proxy for a single call, with an error handler scoped to
    /// that call. `remoteObjectProxyWithErrorHandler`'s error handler fires
    /// *instead of* the reply block on failure, never both — callers must
    /// route both paths through the same completion or it silently never
    /// fires when the daemon is unreachable.
    private func remoteDaemon(onError: @escaping (Error) -> Void) -> NowFocusDaemonProtocol? {
        if connection == nil {
            setupConnection()
        }
        return connection?.remoteObjectProxyWithErrorHandler(onError) as? NowFocusDaemonProtocol
    }

    public func apply(policy: BlockPolicy) {
        guard let daemon = remoteDaemon(onError: { error in
            print("Daemon XPC error while applying policy: \(error)")
        }) else { return }

        do {
            let data = try JSONEncoder().encode(policy)
            daemon.applyPolicy(jsonPayload: data) { success, error in
                if !success {
                    print("Failed to apply policy: \(String(describing: error))")
                }
            }
        } catch {
            print("Failed to encode policy: \(error)")
        }
    }

    public func clear() {
        let semaphore = DispatchSemaphore(value: 0)
        guard let daemon = remoteDaemon(onError: { error in
            print("Daemon XPC error while clearing policy: \(error)")
            semaphore.signal()
        }) else { return }

        daemon.clearPolicy { success, error in
            if !success {
                print("Failed to clear policy: \(String(describing: error))")
            }
            semaphore.signal()
        }
        _ = semaphore.wait(timeout: .now() + 2.0)
    }

    /// A hung LaunchDaemon (e.g. never spawned by launchd — see the ad-hoc
    /// signing / BTM-staleness issue this timeout was added for) never calls
    /// its reply block *and* never triggers the connection's error/invalidation
    /// handlers, since the mach port stays registered even though no process is
    /// behind it. Without a deadline, `didReply` would never flip and the
    /// caller's completion would simply never fire.
    private static let healthCheckTimeout: TimeInterval = 3.0

    public func checkHealth(completion: @escaping (Bool) -> Void) {
        var didReply = false
        // Both the XPC reply and the timeout below must land on the same
        // queue before touching `didReply` — the reply block runs on an
        // XPC-managed background queue, not necessarily main, so funneling
        // both through `.main` is what makes the guard/set race-free.
        let reply: (Bool) -> Void = { success in
            DispatchQueue.main.async {
                guard !didReply else { return }
                didReply = true
                completion(success)
            }
        }

        guard let daemon = remoteDaemon(onError: { error in
            print("Daemon XPC error during health check: \(error)")
            reply(false)
        }) else {
            completion(false)
            return
        }

        daemon.ping { success in reply(success) }

        DispatchQueue.main.asyncAfter(deadline: .now() + Self.healthCheckTimeout) { [weak self] in
            guard !didReply else { return }
            print("Daemon health check timed out after \(Self.healthCheckTimeout)s — invalidating connection")
            didReply = true
            self?.connection?.invalidate()
            self?.connection = nil
            completion(false)
        }
    }

    public func applyCommitment(domains: [String], completion: @escaping (Bool, String?) -> Void) {
        guard let daemon = remoteDaemon(onError: { error in
            completion(false, "\(error)")
        }) else {
            completion(false, loc("Daemon unreachable"))
            return
        }

        do {
            let data = try JSONEncoder().encode(CommitmentApplyRequest(domains: domains))
            daemon.applyCommitment(jsonPayload: data) { success, error in
                completion(success, error.map { "\($0)" })
            }
        } catch {
            completion(false, "\(error)")
        }
    }

    public func clearCommitment(completion: @escaping (Bool, String?) -> Void) {
        guard let daemon = remoteDaemon(onError: { error in
            completion(false, "\(error)")
        }) else {
            completion(false, loc("Daemon unreachable"))
            return
        }

        daemon.clearCommitment { success, message in
            completion(success, message)
        }
    }

    public func fetchCommitmentStatus(completion: @escaping (CommitmentStatusDTO?) -> Void) {
        guard let daemon = remoteDaemon(onError: { _ in completion(nil) }) else {
            completion(nil)
            return
        }

        daemon.commitmentStatus { data in
            guard let data, let status = try? JSONDecoder().decode(CommitmentStatusDTO.self, from: data) else {
                completion(nil)
                return
            }
            completion(status)
        }
    }
}
