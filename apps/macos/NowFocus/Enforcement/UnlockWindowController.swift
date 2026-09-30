import Cocoa
import SwiftUI
import NowFocusCore

/// The unlock flow ("End early?") in its own window, opened from the block screen's "I really need it".
/// The overlay panel can't take typing, and SwiftUI's openWindow isn't reachable from AppKit code, so the
/// same `UnlockOverlayView` the menu bar popover uses is hosted here. Ending the session still goes
/// through `SessionEngine.canCancel`, so this window can't bypass Strict's note or Locked.
@MainActor
final class UnlockWindowController: NSObject, NSWindowDelegate {
    static let shared = UnlockWindowController()
    private var window: NSWindow?

    func present() {
        guard var session = try? DatabaseManager.shared.fetchActiveSession() else { return }
        let engine = SessionEngine()
        engine.evaluateState(for: &session)
        guard engine.isActive(session) else { return }

        if window == nil {
            let view = UnlockOverlayView(
                session: session,
                onConfirmEnd: { [weak self] listened in self?.end(session, listened: listened) },
                onCancel: { [weak self] in self?.close() }
            )
            let host = NSHostingController(rootView: view.frame(width: 360, height: 420))
            let w = NSWindow(contentViewController: host)
            w.title = "NowFocus"
            w.styleMask = [.titled, .closable]
            w.isReleasedWhenClosed = false
            w.level = .floating
            w.delegate = self
            w.center()
            window = w
        }
        // Like Preferences: an accessory app has to be a regular one for a window to take focus.
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
    }

    private func end(_ session: FocusSession, listened: Bool) {
        let hasNote = VoiceNoteStore.shared.hasNote(sessionId: session.id)
        guard SessionEngine.canCancel(mode: session.enforcementMode, unlockCompleted: true, hasVoiceNote: hasNote, listened: listened) else { return }
        SessionController.endSession(session, cancelled: true)
        close()
    }

    private func close() {
        window?.close()
    }

    nonisolated func windowWillClose(_ notification: Notification) {
        Task { @MainActor in self.window = nil }
    }
}
