import Foundation
import AVFoundation

/// Plays a session's voice note in the Strict unlock. `listened` becomes true when playback reaches
/// its natural end, and also when the note can't be played at all (bad file, decode error), so a
/// broken file can never trap anyone: same rule as Android's VoiceNotePlayer.
@Observable
@MainActor
final class VoiceNotePlayer: NSObject {
    private(set) var isPlaying = false
    private(set) var listened = false

    private let url: URL
    private var player: AVAudioPlayer?

    init(url: URL) {
        self.url = url
        super.init()
    }

    func play() {
        guard !isPlaying else { return }
        do {
            #if os(iOS)
            try AVAudioSession.sharedInstance().setCategory(.playback)
            try AVAudioSession.sharedInstance().setActive(true)
            #endif
            let p = try AVAudioPlayer(contentsOf: url)
            p.delegate = self
            player = p
            isPlaying = p.play()
            if !isPlaying { finish() }
        } catch {
            finish()
        }
    }

    func stop() {
        player?.stop()
        player = nil
        isPlaying = false
    }

    private func finish() {
        player = nil
        isPlaying = false
        listened = true
    }
}

extension VoiceNotePlayer: AVAudioPlayerDelegate {
    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in self.finish() }
    }

    nonisolated func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) {
        Task { @MainActor in self.finish() }
    }
}
