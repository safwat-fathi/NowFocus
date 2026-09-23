import Foundation

let delegate = DaemonXPCDelegate()
let listener = NSXPCListener(machServiceName: "app.getnowfocus.daemon")
listener.delegate = delegate
listener.resume()

// Keep the daemon running
RunLoop.main.run()
