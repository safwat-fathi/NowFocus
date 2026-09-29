import SwiftUI
import NowFocusCore

/// Post-onboarding editing surface for the user's motivational data:
/// goals, connections, and voice message. Accessible from the "My Why"
/// sidebar entry in MainWindowView.
struct MyWhyView: View {
    @State private var goals: [UserGoal] = []
    @State private var connections: [UserConnection] = []
    @State private var newGoalText: String = ""
    @State private var newGoalPriority: GoalPriority = .high
    @State private var newConnName: String = ""
    @State private var newConnPhone: String = ""
    @State private var recorder = VoiceRecorder()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: NowFocusSpace.s6) {
                Text("My Why")
                    .font(NowFocusFonts.heading(28))
                    .foregroundColor(NowFocusColors.ink)
                Text("Your goals, connections, and voice message. NowFocus shows these when you're tempted to quit.")
                    .font(NowFocusFonts.body(13))
                    .foregroundColor(NowFocusColors.neutral700)
                    .fixedSize(horizontal: false, vertical: true)

                NowFocusRule(thick: true)

                goalsSection
                NowFocusRule()
                connectionsSection
                NowFocusRule()
                voiceSection
            }
            .padding(NowFocusSpace.s6)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .onAppear(perform: load)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    // MARK: - Load

    private func load() {
        goals       = (try? DatabaseManager.shared.fetchAllGoals()) ?? []
        connections = (try? DatabaseManager.shared.fetchAllConnections()) ?? []
        recorder.checkPermission()
    }

    // MARK: - Goals Section

    private var goalsSection: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s3) {
            sectionLabel("GOALS")

            // Existing goals
            VStack(spacing: 0) {
                ForEach(goals) { goal in
                    HStack(spacing: NowFocusSpace.s2) {
                        NowFocusTagPill(text: goal.priority.label, accent: goal.priority == .high)
                            .frame(width: 42)
                        Text(goal.text)
                            .font(NowFocusFonts.body(14))
                            .foregroundColor(NowFocusColors.ink)
                        Spacer()
                        Button {
                            try? DatabaseManager.shared.deleteGoal(id: goal.id)
                            goals.removeAll { $0.id == goal.id }
                        } label: {
                            Image(systemName: "trash")
                                .foregroundColor(NowFocusColors.neutral500)
                        }
                        .buttonStyle(.plain)
                    }
                    .padding(.vertical, NowFocusSpace.s2)
                    .overlay(alignment: .bottom) { NowFocusRule() }
                }
            }

            // Add row
            HStack(spacing: NowFocusSpace.s2) {
                TextField("Add a goal…", text: $newGoalText)
                    .textFieldStyle(.plain)
                    .font(NowFocusFonts.body(14))
                    .foregroundColor(NowFocusColors.ink)
                    .padding(.vertical, NowFocusSpace.s2)
                    .overlay(NowFocusRule(), alignment: .bottom)
                    .onSubmit { addGoal() }

                NowFocusSegmentedControl(
                    options: GoalPriority.allCases.map { (label: $0.label, value: $0) },
                    selection: $newGoalPriority
                )
                .frame(width: 150)

                NowFocusSecondaryButton(title: "Add") { addGoal() }
            }
        }
    }

    private func addGoal() {
        let text = newGoalText.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return }
        let goal = UserGoal(text: text, priority: newGoalPriority)
        try? DatabaseManager.shared.saveGoal(goal)
        goals.append(goal)
        newGoalText = ""
    }

    // MARK: - Connections Section

    private var connectionsSection: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s3) {
            sectionLabel("CONNECTIONS")

            VStack(spacing: 0) {
                ForEach(connections) { conn in
                    HStack(spacing: NowFocusSpace.s2) {
                        Image(systemName: "person.circle")
                            .foregroundColor(NowFocusColors.neutral500)
                        Text(conn.name)
                            .font(NowFocusFonts.body(14).weight(.semibold))
                            .foregroundColor(NowFocusColors.ink)
                        if let phone = conn.phoneNumber, !phone.isEmpty {
                            Text(phone)
                                .font(NowFocusFonts.body(13))
                                .foregroundColor(NowFocusColors.neutral700)
                        }
                        Spacer()
                        Button {
                            try? DatabaseManager.shared.deleteConnection(id: conn.id)
                            connections.removeAll { $0.id == conn.id }
                        } label: {
                            Image(systemName: "trash")
                                .foregroundColor(NowFocusColors.neutral500)
                        }
                        .buttonStyle(.plain)
                    }
                    .padding(.vertical, NowFocusSpace.s2)
                    .overlay(alignment: .bottom) { NowFocusRule() }
                }
            }

            HStack(spacing: NowFocusSpace.s2) {
                TextField("Name", text: $newConnName)
                    .textFieldStyle(.plain)
                    .font(NowFocusFonts.body(14))
                    .foregroundColor(NowFocusColors.ink)
                    .padding(.vertical, NowFocusSpace.s2)
                    .overlay(NowFocusRule(), alignment: .bottom)
                    .frame(maxWidth: 160)

                TextField("Phone (optional)", text: $newConnPhone)
                    .textFieldStyle(.plain)
                    .font(NowFocusFonts.body(14))
                    .foregroundColor(NowFocusColors.ink)
                    .padding(.vertical, NowFocusSpace.s2)
                    .overlay(NowFocusRule(), alignment: .bottom)

                NowFocusSecondaryButton(title: "Add") { addConnection() }
            }
        }
    }

    private func addConnection() {
        let name = newConnName.trimmingCharacters(in: .whitespaces)
        guard !name.isEmpty else { return }
        let phone = newConnPhone.trimmingCharacters(in: .whitespaces)
        let conn = UserConnection(name: name, phoneNumber: phone.isEmpty ? nil : phone)
        try? DatabaseManager.shared.saveConnection(conn)
        connections.append(conn)
        newConnName = ""
        newConnPhone = ""
    }

    // MARK: - Voice Section

    private var voiceSection: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s3) {
            sectionLabel("VOICE MESSAGE")
            Text("Plays back when you try to unlock a Strict session or cancel a Commitment.")
                .font(NowFocusFonts.body(13))
                .foregroundColor(NowFocusColors.neutral700)

            if recorder.permissionStatus == .denied {
                Text("Microphone access was denied. Enable it in System Settings → Privacy & Security → Microphone.")
                    .font(NowFocusFonts.body(13))
                    .foregroundColor(NowFocusColors.accent800)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(NowFocusSpace.s2)
                    .background(NowFocusColors.accent100)
            } else {
                voiceControls
            }
        }
    }

    private var voiceControls: some View {
        HStack(spacing: NowFocusSpace.s4) {
            // Record / stop button
            Button {
                switch recorder.recorderState {
                case .idle, .recorded: recorder.startRecording()
                case .recording:       recorder.stopRecording()
                case .playing:         recorder.stopPlayback()
                }
            } label: {
                ZStack {
                    Circle()
                        .fill(recorder.recorderState == .recording
                              ? NowFocusColors.accent
                              : NowFocusColors.neutral200)
                        .frame(width: 52, height: 52)
                    Image(systemName: recorder.recorderState == .recording ? "stop.fill" : "mic.fill")
                        .font(.system(size: 20))
                        .foregroundColor(recorder.recorderState == .recording
                                         ? NowFocusColors.ground
                                         : NowFocusColors.ink)
                }
            }
            .buttonStyle(.plain)

            VStack(alignment: .leading, spacing: 2) {
                switch recorder.recorderState {
                case .idle:
                    Text(recorder.permissionStatus == .granted ? "Tap to record" : "Tap to allow mic & record")
                        .font(NowFocusFonts.body(14).weight(.semibold))
                        .foregroundColor(NowFocusColors.ink)
                case .recording:
                    Text(String(format: "Recording… %.0fs / 60s", recorder.recordingDuration))
                        .font(NowFocusFonts.body(14).weight(.semibold))
                        .foregroundColor(NowFocusColors.accent)
                case .recorded:
                    Text("Recording saved")
                        .font(NowFocusFonts.body(14).weight(.semibold))
                        .foregroundColor(NowFocusColors.ink)
                    Text(String(format: "%.0f seconds", recorder.recordingDuration))
                        .font(NowFocusFonts.body(12))
                        .foregroundColor(NowFocusColors.neutral700)
                case .playing:
                    Text(String(format: "Playing… %.0fs", recorder.playbackProgress))
                        .font(NowFocusFonts.body(14).weight(.semibold))
                        .foregroundColor(NowFocusColors.accent)
                }
            }

            if recorder.recorderState == .recorded || recorder.recorderState == .playing {
                Spacer()
                HStack(spacing: NowFocusSpace.s2) {
                    NowFocusSecondaryButton(title: recorder.recorderState == .playing ? "■ Stop" : "▶ Play") {
                        if recorder.recorderState == .playing { recorder.stopPlayback() }
                        else { recorder.startPlayback() }
                    }
                    NowFocusGhostButton(title: "Re-record") { recorder.deleteRecording() }
                }
            }
        }
    }

    // MARK: - Helpers

    private func sectionLabel(_ text: String) -> some View {
        Text(text)
            .font(NowFocusFonts.body(11).weight(.semibold))
            .tracking(1.0)
            .foregroundColor(NowFocusColors.neutral700)
    }
}
