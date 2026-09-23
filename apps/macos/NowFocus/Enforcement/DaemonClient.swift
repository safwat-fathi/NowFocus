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
    
    private var daemon: NowFocusDaemonProtocol? {
        if connection == nil {
            setupConnection()
        }
        return connection?.remoteObjectProxyWithErrorHandler { error in
            print("Daemon XPC Error: \(error)")
        } as? NowFocusDaemonProtocol
    }
    
    public func apply(policy: BlockPolicy) {
        guard let daemon = self.daemon else { return }
        
        do {
            let data = try JSONEncoder().encode(policy)
            daemon.applyPolicy(jsonPayload: data) { success, error in
                if !success {
                    print("Failed to apply policy: \(String(describing: error))")
                } else {
                    print("Policy applied successfully.")
                }
            }
        } catch {
            print("Failed to encode policy: \(error)")
        }
    }
    
    public func clear() {
        guard let daemon = self.daemon else { return }
        daemon.clearPolicy { success, error in
            if !success {
                print("Failed to clear policy: \(String(describing: error))")
            } else {
                print("Policy cleared successfully.")
            }
        }
    }
    
    public func checkHealth(completion: @escaping (Bool) -> Void) {
        guard let daemon = self.daemon else {
            completion(false)
            return
        }
        
        daemon.ping { success in
            completion(success)
        }
    }
}
