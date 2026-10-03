import SwiftUI
import NowFocusCore
import CoreText

@main
struct NowFocusApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    var body: some Scene {
        MenuBarExtra {
            MenuBarView()
        } label: {
            MenuBarIcon()
        }
        .menuBarExtraStyle(.window)

        Window("NowFocus", id: MainWindowView.windowID) {
            MainWindowView()
        }
        .defaultSize(width: 960, height: 640)
    }
}

/// Reads `SessionController.status` directly in `body` so SwiftUI's
/// Observation tracking picks up the swap — a plain closure passed to
/// `MenuBarExtra`'s label wouldn't re-evaluate on its own.
private struct MenuBarIcon: View {
    var body: some View {
        Image(SessionController.status.isActive ? "MenuBarActiveTemplate" : "MenuBarIdleTemplate")
    }
}

@MainActor
class AppDelegate: NSObject, NSApplicationDelegate {
    private var sessionMonitor: Timer?

    func applicationDidFinishLaunching(_ notification: Notification) {
        // Register the Archivo font (Modernist design-system tokens) — no
        // static Info.plist to declare it in (GENERATE_INFOPLIST_FILE: YES),
        // so it's registered programmatically instead.
        if let fontURL = Bundle.main.url(forResource: "archivo_variable", withExtension: "ttf") {
            var registerError: Unmanaged<CFError>?
            let ok = CTFontManagerRegisterFontsForURL(fontURL as CFURL, .process, &registerError)
            if !ok, let registerError {
                print("Archivo font registration failed: \(registerError.takeUnretainedValue())")
            }
            // Debug-only: a variable font registers as one family with N/one
            // named instance (e.g. only "Archivo SemiBold"). If `.weight()`
            // can't reach the heavier weights NowFocusFonts.heading() asks
            // for, this line is how we'd find out — check it in Console
            // before trusting any Archivo heading to actually render heavy.
            print("Archivo family members: \(NSFontManager.shared.availableMembers(ofFontFamily: "Archivo") ?? [])")
        } else {
            print("archivo_variable.ttf not found in bundle — headings will fall back to system font")
        }

        #if DEBUG
        HistoryStats.runSelfCheck()
        BedtimeSchedule.runSelfCheck()
        #endif

        // Initialize DB
        _ = DatabaseManager.shared

        // Seed default policy on first launch
        DatabaseManager.shared.seedDefaultPolicyIfNeeded()

        // Register Daemon
        DaemonRegistrationStatus.shared.refresh()

        // Session Recovery
        recoverSession()

        // Keep enforcement in sync with session expiry even if the menu is
        // never reopened — see SessionController's doc comment.
        sessionMonitor = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.checkSessionExpiry() }
        }

        BedtimeScheduler.shared.start()

        // Optional account sync. Starts nothing, and sends nothing, unless a sign-in is already stored.
        AppSync.start()

        // On first launch, bring the main window forward automatically so the
        // onboarding wizard is visible without requiring the user to find the
        // menu bar icon first.
        if !UserDefaults.standard.bool(forKey: "hasCompletedOnboarding") {
            DispatchQueue.main.async {
                NSApp.setActivationPolicy(.regular)
                NSApp.activate(ignoringOtherApps: true)
                // Post a notification that the SwiftUI openWindow action can't
                // be called from AppDelegate — MainWindowView already renders
                // OnboardingView instead of the sidebar via @AppStorage, and
                // the Window scene in NowFocusApp will open on activation.
                NSApp.windows.first { $0.identifier?.rawValue == MainWindowView.windowID }?.makeKeyAndOrderFront(nil)
            }
        }

        // The menu bar popover has no reason to stay up once a real window (main window, Preferences) takes focus,
        // and it doesn't close itself in that case: close it when a titled window becomes key.
        NotificationCenter.default.addObserver(forName: NSWindow.didBecomeKeyNotification, object: nil, queue: .main) { note in
            guard let key = note.object as? NSWindow, key.styleMask.contains(.titled) else { return }
            NSApp.windows
                .filter { String(describing: type(of: $0)).contains("MenuBarExtraWindow") && $0.isVisible }
                .forEach { $0.orderOut(nil) }
        }

        // Preferences temporarily promotes us to a regular app (Dock/Cmd+Tab)
        // so its window is reachable; drop back to accessory once it closes.
        // willClose fires while the closing window is still visible, so skip it;
        // only titled windows count (overlay panels and the menu bar popover aren't).
        NotificationCenter.default.addObserver(forName: NSWindow.willCloseNotification, object: nil, queue: .main) { note in
            let closing = note.object as? NSWindow
            let otherTitled = NSApp.windows.contains {
                $0 !== closing && $0.isVisible && $0.styleMask.contains(.titled)
            }
            if !otherTitled {
                NSApp.setActivationPolicy(.accessory)
            }
        }
    }

    // Refuse user-initiated quits (menu Quit, Cmd+Q, Dock) while a session is
    // active — quitting would kill AppBlocker. Force Quit can't be stopped;
    // recoverSession() re-applies enforcement on next launch.
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        // Logout/restart/shutdown carry a quit reason; never block those.
        if NSAppleEventManager.shared().currentAppleEvent?
            .attributeDescriptor(forKeyword: kAEQuitReason) != nil {
            return .terminateNow
        }
        guard var session = try? DatabaseManager.shared.fetchActiveSession() else { return .terminateNow }
        let engine = SessionEngine()
        engine.evaluateState(for: &session)
        return engine.isActive(session) ? .terminateCancel : .terminateNow
    }

    private func recoverSession() {
        do {
            guard var session = try DatabaseManager.shared.fetchActiveSession() else {
                // No active session on record. Clear enforcement unconditionally:
                // if a prior session ended while the app was closed, this is what
                // unsticks a /etc/hosts block or overlay left over from it.
                SessionController.stopEnforcement()
                return
            }

            let engine = SessionEngine()
            engine.evaluateState(for: &session)

            if engine.isActive(session) {
                guard let policy = try DatabaseManager.shared.fetchPolicy(id: session.policyId) else {
                    // Policy was deleted while the app was closed (or a pre-fix
                    // orphan) — end the session instead of leaving enforcement stuck.
                    SessionController.endSession(session, cancelled: true)
                    return
                }
                SessionController.startEnforcement(policy: policy, sessionId: session.id, endAt: session.endAt)
            } else {
                // Session expired while we were closed.
                SessionController.endSession(session)
            }
        } catch {
            print("Failed to recover session: \(error)")
        }
    }

    private func checkSessionExpiry() {
        do {
            guard var session = try DatabaseManager.shared.fetchActiveSession() else { return }
            let engine = SessionEngine()
            engine.evaluateState(for: &session)
            if !engine.isActive(session) {
                SessionController.endSession(session)
            }
        } catch {
            print("Failed to check session expiry: \(error)")
        }
    }

    func applicationWillTerminate(_ notification: Notification) {
        do {
            guard var session = try DatabaseManager.shared.fetchActiveSession() else {
                SessionController.stopEnforcement()
                return
            }
            let engine = SessionEngine()
            engine.evaluateState(for: &session)
            if !engine.isActive(session) {
                SessionController.endSession(session)
            }
        } catch {
            print("Failed to clean up session on termination: \(error)")
        }
    }
}
