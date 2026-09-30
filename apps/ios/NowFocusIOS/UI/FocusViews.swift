import NowFocusCore
import SwiftUI

// MARK: - Home

struct HomeView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let today = Calendar.current.startOfDay(for: model.now)
        let live = model.session.map { model.now.timeIntervalSince($0.startAt) } ?? 0
        let focused = HistoryStats.totalFocusedSeconds(model.todaySessions) + max(0, live)
        let turnedAway = HistoryStats.turnedAwayCount(model.blockEvents(from: today, to: today.addingTimeInterval(86_400)))
        let layers = model.enforcer.layers
        let healthy = layers.allSatisfy(\.ok)

        ScreenScaffold(title: "Focus") {
            Kicker(text: "Today")
            Text(Fmt.focused(focused)).font(NowFocusFonts.heading(56))
            HStack(spacing: NowFocusSpace.s2) {
                StatCell(label: "Turned away", value: "\(turnedAway)")
                StatCell(label: "Sessions", value: "\(model.todaySessions.count)")
            }
            HStack {
                NowFocusTagPill(text: healthy ? "Active" : "Degraded", accent: !healthy)
                Text(layers.map { "\($0.name) \($0.ok ? "on" : "off")" }.joined(separator: " · "))
                    .font(NowFocusFonts.body(12)).foregroundColor(NowFocusColors.neutral700)
            }
            NowFocusPrimaryButton(title: model.running ? "Return to session" : "Start focus session") {
                model.screen = model.running ? .active : .setup
            }
            Kicker(text: "Always on").padding(.top, NowFocusSpace.s3)
            NowFocusRule(thick: true)
            RuleRow(title: "Commitment Shield", detail: shieldDetail) { model.backTo = .home; model.screen = .commitment }
            RuleRow(title: "Bedtime Wind-Down", detail: model.bedtime.enabled ? "Tonight \(Fmt.clock(model.bedtime.windDownMinute))" : "Off") {
                model.backTo = .home; model.screen = .bedtime
            }
        }
    }

    private var shieldDetail: String {
        guard let shield = model.shield, model.shieldLive else { return "Off" }
        let days = Int((shield.remaining(now: model.now, uptime: CommitmentShield.uptimeNow()) / 86_400).rounded(.up))
        return "\(days) day\(days == 1 ? "" : "s") to go"
    }
}

// MARK: - Setup

struct SetupView: View {
    @Environment(AppModel.self) private var model
    @State private var policyId: String?
    @State private var minutes = 60
    @State private var mode: EnforcementMode = .normal
    @State private var recorder = VoiceRecorder()

    private static let modeNote: [EnforcementMode: String] = [
        .normal: "End it whenever you like.",
        .strict: "To end early you type a sentence and wait 30 seconds. Optionally leave yourself a note you'll have to hear first.",
        .locked: "No way out until the timer ends.",
    ]

    var body: some View {
        ScreenScaffold(title: "New focus session", onBack: { model.screen = .home }) {
            if model.policies.isEmpty {
                BodyText(text: "No profiles yet.")
                NowFocusSecondaryButton(title: "Manage profiles") { model.screen = .policies }
            } else {
                Kicker(text: "Block")
                ForEach(model.policies) { policy in
                    RuleRow(title: policy.name + (policy.id == (policyId ?? model.policies.first?.id) ? "  ✓" : ""), detail: "\(policy.domains.count) sites") {
                        policyId = policy.id
                    }
                }
                Kicker(text: "For how long").padding(.top, NowFocusSpace.s2)
                FlowChips(items: AppModel.durations.map(\.minutes), label: { m in AppModel.durations.first { $0.minutes == m }!.label },
                          isSelected: { $0 == minutes }, onTap: { minutes = $0 })
                Kicker(text: "How strict").padding(.top, NowFocusSpace.s2)
                NowFocusSegmentedControl(options: [("Normal", EnforcementMode.normal), ("Strict", .strict), ("Locked", .locked)], selection: $mode)
                BodyText(text: Self.modeNote[mode] ?? "", small: true)
                if mode == .strict { voiceNote }
                NowFocusPrimaryButton(title: "Start") {
                    model.startSession(policyId: policyId ?? model.policies[0].id, minutes: minutes, mode: mode)
                }
                .padding(.top, NowFocusSpace.s3)
            }
        }
        .onAppear { recorder.checkPermission() }
    }

    @ViewBuilder private var voiceNote: some View {
        Kicker(text: "Note to yourself (optional)")
        switch recorder.recorderState {
        case .recording:
            HStack {
                Text("Recording… \(Int(recorder.recordingDuration))s / 10s").font(NowFocusFonts.body(14))
                Spacer()
                NowFocusSecondaryButton(title: "Stop") { recorder.stopRecording() }
            }
        case .recorded, .playing:
            HStack {
                Text("Note saved").font(NowFocusFonts.body(14))
                Spacer()
                NowFocusSecondaryButton(title: recorder.recorderState == .playing ? "Stop" : "Play") {
                    recorder.recorderState == .playing ? recorder.stopPlayback() : recorder.startPlayback()
                }
                NowFocusGhostButton(title: "Delete") { recorder.deleteRecording() }
            }
        case .idle:
            NowFocusSecondaryButton(title: "Record 10 seconds") { recorder.startRecording() }
            if recorder.permissionStatus == .denied {
                BodyText(text: "Mic access is off, so Strict will use the typed sentence and 30-second wait.", small: true)
            }
        }
    }
}

// MARK: - Active

struct ActiveView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if let s = model.session {
            let total = s.endAt.timeIntervalSince(s.startAt)
            let done = min(1, max(0, model.now.timeIntervalSince(s.startAt) / total))
            VStack(alignment: .leading, spacing: NowFocusSpace.s4) {
                Text("FOCUS SESSION · \(s.enforcementMode.rawValue.uppercased())")
                    .font(NowFocusFonts.body(11).weight(.semibold)).tracking(1.1).foregroundColor(.white.opacity(0.85))
                Spacer()
                Text(timerInterval: Date.now...max(s.endAt, Date.now), countsDown: true)
                    .font(NowFocusFonts.heading(68)).monospacedDigit().minimumScaleFactor(0.5).lineLimit(1)
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Rectangle().fill(.white.opacity(0.3))
                        Rectangle().fill(.white).frame(width: geo.size.width * done)
                    }
                }
                .frame(height: 6)
                Text(model.policy(s.policyId)?.name ?? "Focus").font(NowFocusFonts.body(15))
                Spacer()
                Button {
                    if s.enforcementMode == .normal { model.cancelSession() } else if s.enforcementMode == .strict { model.screen = .unlock }
                } label: {
                    Text(s.enforcementMode == .locked ? "Locked until timer ends" : "End session early")
                        .font(NowFocusFonts.heading(15))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(NowFocusSpace.s3)
                        .overlay(Rectangle().stroke(.white, lineWidth: 1))
                        .opacity(s.enforcementMode == .locked ? 0.6 : 1)
                }
                .buttonStyle(.plain)
                .disabled(s.enforcementMode == .locked)
            }
            .foregroundColor(.white)
            .padding(NowFocusSpace.s4)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .background(NowFocusColors.accent.ignoresSafeArea(edges: .top))
        }
    }
}

// MARK: - Unlock

struct UnlockView: View {
    @Environment(AppModel.self) private var model
    @State private var typed = ""
    @State private var waitStart: Date?
    @State private var goal: UserGoal?
    @State private var player: VoiceNotePlayer?

    private let waitSeconds = Double(SessionEngine.strictUnlockPauseSeconds)

    var body: some View {
        if let s = model.session {
            ScreenScaffold(title: "End session early?", onBack: { model.screen = .active }) {
                switch s.enforcementMode {
                case .normal:
                    NowFocusPrimaryButton(title: "End session now") { model.cancelSession() }
                case .locked:
                    BodyText(text: "It's locked, by you. This one ends when the timer does.")
                    NowFocusPrimaryButton(title: "Back to focus") { model.screen = .active }
                case .strict:
                    strict(s)
                }
            }
            .onAppear { goal = Goals.pick(model.goals) }
        }
    }

    @ViewBuilder private func strict(_ s: FocusSession) -> some View {
        let hasNote = VoiceNoteStore.shared.hasNote(sessionId: s.id)
        if let waitStart {
            let left = waitSeconds - model.now.timeIntervalSince(waitStart)
            if left > 0 {
                Kicker(text: "Take a moment")
                Text("\(Int(left.rounded(.up)))").font(NowFocusFonts.heading(72)).monospacedDigit()
                if let goal {
                    Kicker(text: "Remember why you started")
                    Text(goal.text).font(NowFocusFonts.heading(20))
                }
            } else {
                if hasNote {
                    let p = player ?? VoiceNotePlayer(url: VoiceNoteStore.shared.noteURL(sessionId: s.id))
                    NowFocusSecondaryButton(title: p.listened ? "Note played" : (p.isPlaying ? "Playing…" : "Hear your note first")) {
                        player = p
                        p.play()
                    }
                    NowFocusPrimaryButton(title: "End session now", enabled: p.listened) {
                        model.cancelSession(unlockCompleted: true, listened: p.listened)
                    }
                } else {
                    NowFocusPrimaryButton(title: "End session now") { model.cancelSession(unlockCompleted: true) }
                }
            }
        } else {
            BodyText(text: "Type this sentence exactly:")
            Text(SessionEngine.strictUnlockSentence).font(NowFocusFonts.heading(17))
            TextField("", text: $typed).nfField()
            NowFocusPrimaryButton(title: "Continue", enabled: typed.trimmingCharacters(in: .whitespacesAndNewlines) == SessionEngine.strictUnlockSentence) {
                waitStart = Date()
            }
        }
    }
}
