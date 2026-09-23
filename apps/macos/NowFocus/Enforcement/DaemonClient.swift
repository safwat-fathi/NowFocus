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
        guard let daemon = remoteDaemon(onError: { error in
            print("Daemon XPC error while clearing policy: \(error)")
        }) else { return }

        daemon.clearPolicy { success, error in
            if !success {
                print("Failed to clear policy: \(String(describing: error))")
            }
        }
    }

    public func checkHealth(completion: @escaping (Bool) -> Void) {
        var didReply = false
        let reply: (Bool) -> Void = { success in
            guard !didReply else { return }
            didReply = true
            completion(success)
        }

        guard let daemon = remoteDaemon(onError: { error in
            print("Daemon XPC error during health check: \(error)")
            reply(false)
        }) else {
            completion(false)
            return
        }

        daemon.ping { success in reply(success) }
    }
}
