import Foundation

/// Manages the voice message file stored in Application Support.
/// The file lives on the filesystem (not in SQLite) — binary blobs in
/// SQLite hurt read performance and the path is deterministic anyway.
public final class VoiceMessageStore {
    public static let shared = VoiceMessageStore()
    private init() {}

    /// The canonical URL for the recorded voice message.
    public var voiceMessageURL: URL {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let dir = appSupport.appendingPathComponent("NowFocus", isDirectory: true)
        // Directory is created by DatabaseManager.init already, but be safe.
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("voice_message.m4a")
    }

    /// True when a recorded file exists at the canonical path.
    public var hasVoiceMessage: Bool {
        FileManager.default.fileExists(atPath: voiceMessageURL.path)
    }

    /// Deletes the voice message if it exists. Silently ignores missing files.
    public func deleteVoiceMessage() {
        try? FileManager.default.removeItem(at: voiceMessageURL)
    }
}
