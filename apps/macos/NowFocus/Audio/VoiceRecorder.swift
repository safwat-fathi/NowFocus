import Foundation
import AVFoundation
import NowFocusCore

/// Records the optional note to yourself for a Strict session: at most 10 seconds, saved as the
/// pending note (`VoiceNoteStore.pendingURL`) until a Strict session starts and adopts it.
///
/// Marked `@Observable` so SwiftUI views reactively update on state changes
/// without manual Combine plumbing.
@Observable
@MainActor
final class VoiceRecorder: NSObject {

    // MARK: - State

    enum RecorderState {
        case idle           // No recording exists yet (or was deleted)
        case recording      // Actively capturing audio
        case recorded       // Recording complete, ready to play / re-record
        case playing        // Playback in progress
    }

    enum PermissionStatus {
        case unknown, granted, denied
    }

    var recorderState: RecorderState = .idle
    var permissionStatus: PermissionStatus = .unknown
    var recordingDuration: TimeInterval = 0   // seconds elapsed while recording
    var playbackProgress: TimeInterval = 0    // seconds elapsed during playback

    static let maxDuration: TimeInterval = 10

    // MARK: - Private

    private var audioRecorder: AVAudioRecorder?
    private var audioPlayer: AVAudioPlayer?
    private var recordingTimer: Timer?
    private var playbackTimer: Timer?

    private let store = VoiceNoteStore.shared

    // MARK: - Init

    override init() {
        super.init()
        recorderState = store.hasPending ? .recorded : .idle
    }

    // MARK: - Permission

    func requestPermission() {
        AVCaptureDevice.requestAccess(for: .audio) { [weak self] granted in
            Task { @MainActor [weak self] in
                self?.permissionStatus = granted ? .granted : .denied
            }
        }
    }

    func checkPermission() {
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized:             permissionStatus = .granted
        case .denied, .restricted:   permissionStatus = .denied
        case .notDetermined:          permissionStatus = .unknown
        @unknown default:             permissionStatus = .unknown
        }
    }

    // MARK: - Recording

    func startRecording() {
        guard permissionStatus == .granted else {
            requestPermission()
            return
        }

        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: 44100,
            AVNumberOfChannelsKey: 1,
            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue
        ]

        do {
            store.deletePending()
            audioRecorder = try AVAudioRecorder(url: store.pendingURL, settings: settings)
            audioRecorder?.delegate = self
            audioRecorder?.record()
            recorderState = .recording
            recordingDuration = 0

            recordingTimer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
                guard let self else { return }
                Task { @MainActor in
                    self.recordingDuration += 0.1
                    if self.recordingDuration >= VoiceRecorder.maxDuration {
                        self.stopRecording()
                    }
                }
            }
        } catch {
            print("VoiceRecorder: failed to start recording: \(error)")
        }
    }

    func stopRecording() {
        recordingTimer?.invalidate()
        recordingTimer = nil
        audioRecorder?.stop()
        audioRecorder = nil
        // A file that can't be played back would leave a Strict session with a note nobody can hear:
        // discard it, the same as a note that was never recorded.
        if store.hasPending, (try? AVAudioPlayer(contentsOf: store.pendingURL)) != nil {
            recorderState = .recorded
        } else {
            store.deletePending()
            recorderState = .idle
        }
    }

    func deleteRecording() {
        stopRecording()
        stopPlayback()
        store.deletePending()
        recordingDuration = 0
        playbackProgress = 0
        recorderState = .idle
    }

    // MARK: - Playback

    func startPlayback() {
        guard store.hasPending else { return }
        do {
            audioPlayer = try AVAudioPlayer(contentsOf: store.pendingURL)
            audioPlayer?.delegate = self
            audioPlayer?.play()
            recorderState = .playing
            playbackProgress = 0

            playbackTimer = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { [weak self] _ in
                guard let self else { return }
                Task { @MainActor in
                    self.playbackProgress = self.audioPlayer?.currentTime ?? 0
                }
            }
        } catch {
            print("VoiceRecorder: failed to start playback: \(error)")
        }
    }

    func stopPlayback() {
        playbackTimer?.invalidate()
        playbackTimer = nil
        audioPlayer?.stop()
        audioPlayer = nil
        if recorderState == .playing {
            recorderState = .recorded
        }
        playbackProgress = 0
    }
}

// MARK: - AVAudioRecorderDelegate

extension VoiceRecorder: AVAudioRecorderDelegate {
    nonisolated func audioRecorderDidFinishRecording(_ recorder: AVAudioRecorder, successfully flag: Bool) {
        Task { @MainActor in
            if !flag { self.recorderState = .idle }
        }
    }
}

// MARK: - AVAudioPlayerDelegate

extension VoiceRecorder: AVAudioPlayerDelegate {
    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in
            self.stopPlayback()
        }
    }
}
