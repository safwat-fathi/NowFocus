import Foundation
import NowFocusCore
import Observation
import UIKit

/// Mirrors Android's SessionViewModel + the screen state machine in MainActivity. Session state is
/// always re-derived from startAt/endAt/now through `SessionEngine`, never from a running timer.
@MainActor
@Observable
final class AppModel {
    enum Screen: Equatable {
        case home, setup, active, unlock, policies, editPolicy(String), stats
        case commitment, bedtime, devices, onboarding, people, goals
    }

    var screen: Screen
    /// Commitment and Bedtime open from both Home and Rules; Back returns to whichever opened them.
    var backTo: Screen = .home
    private(set) var now = Date()
    private(set) var session: FocusSession?
    private(set) var policies: [BlockPolicy] = []
    private(set) var goals: [UserGoal] = []
    private(set) var people: [UserConnection] = []
    private(set) var todaySessions: [FocusSession] = []
    private(set) var shield: CommitmentShield?
    private(set) var onboardingDone: Bool
    var bedtime: BedtimeSettings {
        didSet { BedtimeSettingsStore.shared.settings = bedtime }
    }
    @ObservationIgnored var enforcer: Enforcer = NoopEnforcer()

    @ObservationIgnored private let db = DatabaseManager.shared
    @ObservationIgnored private let engine = SessionEngine()
    @ObservationIgnored private let defaults = UserDefaults.standard
    @ObservationIgnored private let deviceId = UIDevice.current.identifierForVendor?.uuidString ?? "ios"

    static let durations: [(label: String, minutes: Int)] = [
        ("25m", 25), ("45m", 45), ("1h", 60), ("1.5h", 90), ("2h", 120), ("3h", 180), ("4h", 240),
    ]

    init() {
        db.seedDefaultPolicyIfNeeded()
        bedtime = BedtimeSettingsStore.shared.settings
        onboardingDone = defaults.bool(forKey: "onboardingDone")
        screen = onboardingDone ? .home : .onboarding
        shield = defaults.data(forKey: "commitmentShield").flatMap { try? JSONDecoder().decode(CommitmentShield.self, from: $0) }
        refresh()
    }

    // MARK: - Derived

    var running: Bool { session.map { engine.isActive($0, currentTime: now) } ?? false }

    var shieldLive: Bool { shield.map { !$0.isOver(now: now, uptime: CommitmentShield.uptimeNow()) } ?? false }

    func policy(_ id: String?) -> BlockPolicy? { policies.first { $0.id == id } }

    /// Removing rules from the profile a running session uses would loosen it; additions are fine.
    func canWeaken(policyId: String) -> Bool { !(running && session?.policyId == policyId) }

    // MARK: - Refresh and routing

    /// Re-derives everything from storage and the clock. Called on launch, on foreground and whenever a
    /// session or the shield passes its end.
    func refresh() {
        now = Date()
        if var s = (try? db.fetchActiveSession()) ?? nil {
            let before = s.status
            engine.evaluateState(for: &s, currentTime: now)
            if s.status != before { try? db.saveSession(s) }
            session = engine.isActive(s, currentTime: now) ? s : nil
        } else {
            session = nil
        }
        if let shield, shield.isOver(now: now, uptime: CommitmentShield.uptimeNow()) { storeShield(nil) }
        policies = (try? db.fetchAllPolicies()) ?? []
        goals = (try? db.fetchAllGoals()) ?? []
        people = (try? db.fetchAllConnections()) ?? []
        let dayStart = Calendar.current.startOfDay(for: now)
        todaySessions = (try? db.fetchSessions(from: dayStart, to: dayStart.addingTimeInterval(86_400), sessionType: .focus)) ?? []
        syncEnforcement()
        route()
    }

    /// 1 Hz: only touches storage when something just ended.
    func tick() {
        now = Date()
        if let session, now >= session.endAt { refresh() }
        else if let shield, shield.isOver(now: now, uptime: CommitmentShield.uptimeNow()) { refresh() }
    }

    private func route() {
        if !onboardingDone {
            if screen == .home { screen = .onboarding }
            return
        }
        if running && screen == .home { screen = .active }
        if !running && (screen == .active || screen == .unlock) { screen = .home }
    }

    private func syncEnforcement() {
        var blocked = BlockedSet()
        if running, let session, let policy = policy(session.policyId) {
            blocked.domains.formUnion(policy.domains.filter(\.enabled).map(\.domain))
            blocked.endAt = session.endAt
        }
        if let shield, shieldLive {
            blocked.domains.formUnion(shield.domains)
            blocked.endAt = max(blocked.endAt ?? shield.endAt, shield.endAt)
        }
        enforcer.apply(blocked)
        LiveActivityController.sync(session: session, policyName: policy(session?.policyId)?.name ?? "Focus", now: now)
    }

    // MARK: - Sessions

    func startSession(policyId: String, minutes: Int, mode: EnforcementMode) {
        guard !running, policy(policyId) != nil else { return }
        let start = Date()
        let new = FocusSession(
            policyId: policyId, startAt: start, endAt: start.addingTimeInterval(TimeInterval(minutes * 60)),
            status: .active, enforcementMode: mode, deviceId: deviceId
        )
        // Only Strict uses a note; purging on every start also clears the last session's note.
        if mode == .strict { VoiceNoteStore.shared.adoptPending(as: new.id) }
        VoiceNoteStore.shared.purge(except: mode == .strict ? new.id : nil)
        try? db.saveSession(new)
        refresh()
        screen = .active
    }

    /// The only path that ends a session early; `SessionEngine.canCancel` decides whether it may.
    @discardableResult
    func cancelSession(unlockCompleted: Bool = false, listened: Bool = false) -> Bool {
        guard var s = session else { return false }
        let hasNote = VoiceNoteStore.shared.hasNote(sessionId: s.id)
        guard SessionEngine.canCancel(mode: s.enforcementMode, unlockCompleted: unlockCompleted, hasVoiceNote: hasNote, listened: listened) else { return false }
        s.status = .cancelled
        s.cancelledAt = Date()
        try? db.saveSession(s)
        refresh()
        return true
    }

    // MARK: - Policies

    func savePolicy(_ policy: BlockPolicy) {
        var p = policy
        p.updatedAt = Date()
        try? db.savePolicy(p)
        refresh()
    }

    func addPolicy() -> String {
        let p = BlockPolicy(name: "New profile")
        try? db.savePolicy(p)
        refresh()
        return p.id
    }

    func deletePolicy(_ id: String) {
        guard canWeaken(policyId: id) else { return }
        try? db.deletePolicy(id: id)
        refresh()
    }

    // MARK: - Commitment Shield

    private func storeShield(_ new: CommitmentShield?) {
        shield = new
        defaults.set(new.flatMap { try? JSONEncoder().encode($0) }, forKey: "commitmentShield")
    }

    /// Refused while an earlier shield is still running, like Android.
    func createShield(domains: [String]) {
        guard !shieldLive, !domains.isEmpty else { return }
        storeShield(CommitmentShield(startAt: Date(), domains: domains, createdUptime: CommitmentShield.uptimeNow()))
        refresh()
    }

    func cancelShield() {
        guard let shield, shield.canCancel(now: Date(), uptime: CommitmentShield.uptimeNow()) else { return }
        storeShield(nil)
        refresh()
    }

    // MARK: - Goals and people

    func addGoal(_ text: String, priority: GoalPriority) {
        let text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        try? db.saveGoal(UserGoal(text: text, priority: priority))
        refresh()
    }

    func removeGoal(_ id: String) { try? db.deleteGoal(id: id); refresh() }

    /// False when the number is blank, repeated, or the list is full.
    @discardableResult
    func addPerson(name: String, phone: String) -> Bool {
        guard PeopleRotation.canAdd(phone: phone, to: people) else { return false }
        try? db.saveConnection(UserConnection(name: name, phoneNumber: phone.trimmingCharacters(in: .whitespaces)))
        refresh()
        return true
    }

    func removePerson(_ id: String) { try? db.deleteConnection(id: id); refresh() }

    func setLastTalked(_ id: String, daysAgo: Int?) {
        try? db.setLastTalked(connectionId: id, to: LastTalked.date(daysAgo: daysAgo, now: Date()))
        refresh()
    }

    func markTalked(_ id: String) { try? db.markTalked(connectionId: id); refresh() }

    // MARK: - Onboarding and stats

    func completeOnboarding() {
        onboardingDone = true
        defaults.set(true, forKey: "onboardingDone")
        screen = .home
    }

    func sessions(from: Date, to: Date) -> [FocusSession] {
        (try? db.fetchSessions(from: from, to: to, sessionType: .focus)) ?? []
    }

    func blockEvents(from: Date, to: Date) -> [SessionEvent] {
        (try? db.fetchEvents(from: from, to: to, type: "app_blocked")) ?? []
    }
}
