import Foundation

/// The optional 10-second voice notes, stored as plain files (not in SQLite). Same model as Android's
/// `VoiceNote`: a note recorded on the Setup screen sits in `pending.m4a` until a Strict session
/// starts and adopts it as `<sessionId>.m4a`; every other note is purged at each session start. A
/// file's existence (and being non-empty) IS "has a note": no database column to fall out of sync.
public final class VoiceNoteStore {
    public static let shared = VoiceNoteStore(
        directory: FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("NowFocus/voice", isDirectory: true)
    )

    private let directory: URL
    private let fm = FileManager.default

    public init(directory: URL) {
        self.directory = directory
        try? fm.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    /// Where a note being recorded (not yet tied to a session) lives.
    public var pendingURL: URL { directory.appendingPathComponent("pending.m4a") }

    public func noteURL(sessionId: String) -> URL { directory.appendingPathComponent("\(sessionId).m4a") }

    public var hasPending: Bool { isNonEmptyFile(pendingURL) }

    public func hasNote(sessionId: String) -> Bool { isNonEmptyFile(noteURL(sessionId: sessionId)) }

    /// Moves the pending note to this session. False when there was nothing to adopt.
    @discardableResult
    public func adoptPending(as sessionId: String) -> Bool {
        guard hasPending else { return false }
        let target = noteURL(sessionId: sessionId)
        try? fm.removeItem(at: target)
        do {
            try fm.moveItem(at: pendingURL, to: target)
            return true
        } catch {
            return false
        }
    }

    /// Deletes every note except the one for `sessionId` (pending included); nil deletes them all.
    public func purge(except sessionId: String?) {
        let keep = sessionId.map { "\($0).m4a" }
        let files = (try? fm.contentsOfDirectory(atPath: directory.path)) ?? []
        for name in files where name != keep {
            try? fm.removeItem(at: directory.appendingPathComponent(name))
        }
    }

    public func deletePending() { try? fm.removeItem(at: pendingURL) }

    /// The single global note from before per-session notes: it can never be played back now.
    public func removeLegacyGlobalNote() {
        let legacy = directory.deletingLastPathComponent().appendingPathComponent("voice_message.m4a")
        try? fm.removeItem(at: legacy)
    }

    private func isNonEmptyFile(_ url: URL) -> Bool {
        let size = (try? fm.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.intValue ?? 0
        return size > 0
    }
}
