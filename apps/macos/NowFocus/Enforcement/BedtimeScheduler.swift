import Foundation
import NowFocusCore

/// Whether locking the screen at the sleep moment will actually lock it, not
/// just blank the display — read-only, checked without ever triggering a
/// lock. macOS has no public "lock now" API for a regular app; the one
/// permission-free mechanism is `pmset displaysleepnow`, and it only locks if
/// the user's own "require password after sleep" is set to immediate.
///
/// `com.apple.screensaver`'s `askForPassword`/`askForPasswordDelay` keys
/// (the traditional read for this) come back simply absent on modern macOS —
/// confirmed on this machine — so this shells out to `sysadminctl -screenLock
/// status` instead, which is read-only (no lock triggered, no privileges
/// needed) and reports a real value here (e.g. "screenLock delay is 60
/// seconds").
enum BedtimeLockAvailability {
    static func check() -> (available: Bool, reason: String?) {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/sbin/sysadminctl")
        process.arguments = ["-screenLock", "status"]
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = pipe // sysadminctl logs its result to stderr

        do {
            try process.run()
        } catch {
            return (false, "Couldn't check your screen lock delay (\(error)). Set \u{201C}Require password after sleep\u{201D} to \u{201C}Immediately\u{201D} in System Settings \u{2192} Lock Screen.")
        }
        process.waitUntilExit()

        let output = String(data: pipe.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8) ?? ""
        let lower = output.lowercased()
        if lower.contains("immediately") {
            return (true, nil)
        }
        guard let seconds = secondsValue(in: output) else {
            return (false, "Couldn't determine your screen lock delay. Set \u{201C}Require password after sleep\u{201D} to \u{201C}Immediately\u{201D} in System Settings \u{2192} Lock Screen.")
        }
        if seconds == 0 {
            return (true, nil)
        }
        return (false, "Your Mac currently waits \(seconds)s after sleep before requiring a password. Set \u{201C}Require password after sleep\u{201D} to \u{201C}Immediately\u{201D} in System Settings \u{2192} Lock Screen for this to actually lock right away.")
    }

    /// Extracts the number immediately preceding "second" — not just the
    /// first digits in the string, since sysadminctl's own log prefix (a
    /// timestamp, a PID) contains plenty of unrelated digits first.
    private static func secondsValue(in text: String) -> Int? {
        guard let range = text.range(of: #"(\d+)\s*second"#, options: .regularExpression) else { return nil }
        return Int(text[range].filter(\.isNumber))
    }
}

/// Runs Bedtime Wind-Down as a scheduled `.locked` `FocusSession` — reusing
/// `SessionController` and `AppDelegate`'s existing expiry timer entirely,
/// rather than a parallel enforcement path. App-must-be-running (per
/// native_tech_stack_spec.md giving the user-level app session-engine
/// ownership); there is no daemon/LaunchAgent wake mechanism here.
@MainActor
final class BedtimeScheduler {
    static let shared = BedtimeScheduler()
    private var timer: Timer?

    // In-memory only, and deliberately not "has this fired today" — recomputed
    // from `BedtimeSchedule.isAtSleepMoment`'s own ~2-minute window every tick,
    // so a relaunch mid-window can't cause a spurious immediate lock (see
    // isAtSleepMoment's doc comment) while still being idempotent within that
    // window.
    private var lastSleepLockAt: Date?

    private init() {}

    func start() {
        stop()
        timer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            self?.tick()
        }
        tick()
    }

    func stop() {
        timer?.invalidate()
        timer = nil
    }

    private func tick() {
        let settings = BedtimeSettingsStore.shared.settings
        guard settings.enabled, let policyId = settings.policyId else { return }

        let now = Date()
        let calendar = Calendar.current

        guard let window = BedtimeSchedule.currentWindow(settings, now: now, calendar: calendar) else { return }

        startSessionIfNeeded(policyId: policyId, window: window)

        if settings.lockAtSleep,
           BedtimeSchedule.isAtSleepMoment(settings, now: now, window: window, calendar: calendar),
           lastSleepLockAt == nil || now.timeIntervalSince(lastSleepLockAt!) > 120 {
            lastSleepLockAt = now
            if BedtimeLockAvailability.check().available {
                lockScreenNow()
            }
        }
    }

    private func startSessionIfNeeded(policyId: String, window: (start: Date, end: Date)) {
        // Skip while *anything* is active, not just an existing bedtime
        // session — a running focus session shares the same "one active
        // session" daemon/AppBlocker state, so starting a second session here
        // would let either one's end silently clear the other's enforcement.
        do {
            if try DatabaseManager.shared.fetchActiveSession() != nil { return }
        } catch {
            print("Failed to check for an active session before starting Bedtime: \(error)")
            return
        }
        guard let policy = try? DatabaseManager.shared.fetchPolicy(id: policyId) else { return }

        let session = FocusSession(
            policyId: policy.id,
            sessionType: .bedtime_winddown,
            startAt: window.start,
            endAt: window.end,
            enforcementMode: .locked,
            deviceId: "local"
        )

        do {
            try DatabaseManager.shared.saveSession(session)
            SessionController.startEnforcement(policy: policy, sessionId: session.id, endAt: session.endAt, sessionType: session.sessionType)
        } catch {
            print("Failed to start Bedtime session: \(error)")
        }
    }

    /// Not exercised by any automated check in this codebase — locking the
    /// screen is disruptive by nature, so this specific action needs a manual
    /// verification pass, not an automated one.
    private func lockScreenNow() {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/pmset")
        process.arguments = ["displaysleepnow"]
        do {
            try process.run()
        } catch {
            print("Failed to put display to sleep for Bedtime lock: \(error)")
        }
    }
}
