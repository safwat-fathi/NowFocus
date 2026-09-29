import SwiftUI
import NowFocusCore
import AVFoundation

/// Inline overlay (not a system sheet — a `MenuBarExtra` popover hosts modal
/// sheets unreliably) shown when the user tries to end a Strict or Locked
/// session early. Content is entirely driven by `SessionEngine.stopGate`, so
/// this view has no opinion of its own about which modes get which flow.
struct UnlockOverlayView: View {
    let session: FocusSession
    let onConfirmEnd: () -> Void
    let onCancel: () -> Void

    @State private var typed: String = ""
    @State private var pauseStart: Date?
    @State private var pauseEnd: Date?
    @State private var now = Date()
    @State private var motivationGoal: UserGoal? = nil
    @State private var voiceRecorder = VoiceRecorder()

    private let engine = SessionEngine()
    private let ticker = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        ZStack {
            NowFocusColors.ink.opacity(0.55)

            VStack(alignment: .leading, spacing: NowFocusSpace.s3) {
                HStack {
                    Text(pauseEnd == nil ? "End early?" : "One moment")
                        .font(NowFocusFonts.heading(18))
                        .foregroundColor(NowFocusColors.ink)
                    Spacer()
                    Button(action: onCancel) {
                        Image(systemName: "xmark")
                            .foregroundColor(NowFocusColors.neutral700)
                    }
                    .buttonStyle(.plain)
                }

                content
            }
            .padding(NowFocusSpace.s4)
            .frame(width: 300)
            .background(NowFocusColors.ground)
        }
        .onReceive(ticker) { tick in now = tick }
        .onAppear {
            motivationGoal = try? DatabaseManager.shared.fetchRandomGoal()
            voiceRecorder.checkPermission()
        }
        .onDisappear {
            voiceRecorder.stopPlayback()
        }
    }

    private var pauseElapsed: Bool {
        guard let pauseEnd else { return false }
        return now >= pauseEnd
    }

    @ViewBuilder
    private var content: some View {
        switch engine.stopGate(for: session) {
        case .immediate:
            // Normal mode never routes here — MenuBarView ends the session
            // directly without presenting this overlay.
            EmptyView()

        case .locked:
            VStack(alignment: .leading, spacing: NowFocusSpace.s3) {
                Text("This one's locked, by you.")
                    .font(NowFocusFonts.body(14))
                    .foregroundColor(NowFocusColors.neutral800)
                NowFocusPrimaryButton(title: "Back to focus", action: onCancel)
            }

        case .requiresUnlock(let sentence, let pauseSeconds):
            if pauseEnd != nil {
                pauseStage(pauseSeconds: pauseSeconds)
            } else {
                sentenceStage(sentence: sentence, pauseSeconds: pauseSeconds)
            }
        }
    }

    @ViewBuilder
    private func sentenceStage(sentence: String, pauseSeconds: Int) -> some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s2) {
            Text("Type this out, word for word, so it's a choice and not a reflex.")
                .font(NowFocusFonts.body(13))
                .foregroundColor(NowFocusColors.neutral800)
            Text("\u{201C}\(sentence)\u{201D}")
                .font(NowFocusFonts.heading(15))
                .foregroundColor(NowFocusColors.ink)
            TextField("Type the sentence…", text: $typed)
                .textFieldStyle(.plain)
                .font(NowFocusFonts.body(14))
                .foregroundColor(NowFocusColors.ink)
                .padding(.vertical, NowFocusSpace.s2)
                .overlay(NowFocusRule(), alignment: .bottom)
            HStack(spacing: NowFocusSpace.s2) {
                NowFocusPrimaryButton(title: "Stay focused", action: onCancel)
                NowFocusSecondaryButton(title: "Start \(pauseSeconds)s pause") {
                    guard typed == sentence else { return }
                    pauseStart = now
                    pauseEnd = now.addingTimeInterval(TimeInterval(pauseSeconds))
                }
            }
        }
    }

    @ViewBuilder
    private func pauseStage(pauseSeconds: Int) -> some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s2) {
            Text("Take a breath. If you still want out when this hits zero, it's yours.")
                .font(NowFocusFonts.body(13))
                .foregroundColor(NowFocusColors.neutral800)

            if let pauseStart, let pauseEnd {
                Text(timerInterval: pauseStart...pauseEnd, countsDown: true)
                    .font(NowFocusFonts.heading(40))
                    .foregroundColor(NowFocusColors.accent)
                    .monospacedDigit()
            }

            // Goal reminder
            if let goal = motivationGoal {
                HStack(alignment: .top, spacing: NowFocusSpace.s2) {
                    Image(systemName: "flame.fill")
                        .foregroundColor(NowFocusColors.accent)
                        .font(.system(size: 12))
                        .padding(.top, 2)
                    Text("Remember why you started: \(goal.text)")
                        .font(NowFocusFonts.body(13).weight(.semibold))
                        .foregroundColor(NowFocusColors.ink)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(NowFocusSpace.s2)
                .background(NowFocusColors.neutral100)
            }

            // Voice message playback
            if VoiceMessageStore.shared.hasVoiceMessage {
                voiceMessageButton
            }

            HStack(spacing: NowFocusSpace.s2) {
                NowFocusPrimaryButton(title: "Stay focused", action: onCancel)
                NowFocusSecondaryButton(
                    title: pauseElapsed ? "End session now" : "Wait…",
                    action: { if pauseElapsed { onConfirmEnd() } }
                )
            }
        }
    }

    @ViewBuilder
    private var voiceMessageButton: some View {
        let isPlaying = voiceRecorder.recorderState == .playing
        Button {
            if isPlaying { voiceRecorder.stopPlayback() }
            else { voiceRecorder.startPlayback() }
        } label: {
            HStack(spacing: NowFocusSpace.s2) {
                Image(systemName: isPlaying ? "pause.fill" : "headphones")
                    .font(.system(size: 13))
                Text(isPlaying ? "Stop message" : "🎧 Listen to your message")
                    .font(NowFocusFonts.body(13))
            }
            .foregroundColor(NowFocusColors.accent)
            .padding(.vertical, NowFocusSpace.s1)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
