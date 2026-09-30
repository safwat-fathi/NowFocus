import Foundation
import NowFocusCore

/// One layer of blocking and whether it's working, for the Devices screen.
struct EnforcementLayer: Identifiable {
    let name: String
    let ok: Bool
    /// Button title and action that gets the user to the missing permission; nil when nothing can be done in-app.
    let fixTitle: String?
    let fix: (() -> Void)?
    var id: String { name }
}

/// What must be blocked right now (session and Commitment Shield merged), or nothing to clear.
struct BlockedSet: Equatable {
    var domains: Set<String> = []
    var apps: [String] = []   // opaque Screen Time selection data, once it exists
    var endAt: Date?
    var isEmpty: Bool { domains.isEmpty && apps.isEmpty }
}

/// The one seam between session logic and the OS. `apply` is called from every start, cancel and
/// recovery path with the full current set, so an empty set clears. `NoopEnforcer` until
/// Screen Time access exists (paid Apple account); the real one replaces it without touching callers.
@MainActor
protocol Enforcer {
    var layers: [EnforcementLayer] { get }
    func apply(_ blocked: BlockedSet)
}

@MainActor
struct NoopEnforcer: Enforcer {
    var layers: [EnforcementLayer] {
        [
            EnforcementLayer(name: "App blocking", ok: false, fixTitle: nil, fix: nil),
            EnforcementLayer(name: "Website filter", ok: false, fixTitle: nil, fix: nil),
        ]
    }
    func apply(_ blocked: BlockedSet) {}
}
