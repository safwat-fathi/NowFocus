import Foundation
import ServiceManagement

let service = SMAppService.daemon(plistName: "com.getnowfocus.daemon.plist")
print("Daemon Status: \(service.status.rawValue)")
