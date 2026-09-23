import Foundation
import NowFocusCore

class DaemonXPCDelegate: NSObject, NSXPCListenerDelegate, NowFocusDaemonProtocol {
    
    private let enforcer = NetworkEnforcer()
    
    func listener(_ listener: NSXPCListener, shouldAcceptNewConnection newConnection: NSXPCConnection) -> Bool {
        // For the MVP without full code signing, we accept all connections.
        // In production, validate the connecting process via SecCodeCheckValidity
        // using the connection's audit token and a code signing requirement.
        
        newConnection.exportedInterface = NSXPCInterface(with: NowFocusDaemonProtocol.self)
        newConnection.exportedObject = self
        newConnection.resume()
        return true
    }
    
    func applyPolicy(jsonPayload: Data, withReply reply: @escaping (Bool, Error?) -> Void) {
        do {
            let policy = try JSONDecoder().decode(BlockPolicy.self, from: jsonPayload)
            try enforcer.apply(policy: policy)
            reply(true, nil)
        } catch {
            reply(false, error)
        }
    }
    
    func clearPolicy(withReply reply: @escaping (Bool, Error?) -> Void) {
        do {
            try enforcer.clear()
            reply(true, nil)
        } catch {
            reply(false, error)
        }
    }
    
    func ping(withReply reply: @escaping (Bool) -> Void) {
        reply(true)
    }
}
