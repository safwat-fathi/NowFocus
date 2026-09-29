import SwiftUI
import NowFocusCore

// MARK: - Container

/// Multi-step onboarding wizard shown on first launch.
/// Replaces the main window content until `onComplete` is called.
struct OnboardingView: View {
    let onComplete: () -> Void

    @State private var step: Int = 0

    // Step 1 – Schedule
    @State private var workStart: Date = minutesToDate(9 * 60)
    @State private var workEnd: Date   = minutesToDate(17 * 60)
    @State private var sleepTime: Date = minutesToDate(23 * 60)
    @State private var wakeTime: Date  = minutesToDate(7 * 60)

    // Step 2 – Goals
    @State private var goals: [UserGoal] = []
    @State private var newGoalText: String = ""
    @State private var newGoalPriority: GoalPriority = .high
    @State private var goalError: String? = nil

    // Step 3 – Connections
    @State private var connections: [UserConnection] = []
    @State private var newConnName: String = ""
    @State private var newConnPhone: String = ""

    // Step 4 – Voice
    @State private var recorder = VoiceRecorder()

    private let totalSteps = 5  // 0..4

    var body: some View {
        HStack(spacing: 0) {
            // Left brand rail
            brandRail

            NowFocusRule(vertical: true)

            // Right content pane
            VStack(alignment: .leading, spacing: 0) {
                stepIndicator
                    .padding(.bottom, NowFocusSpace.s6)

                stepContent
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                    .transition(.asymmetric(
                        insertion: .move(edge: .trailing).combined(with: .opacity),
                        removal:   .move(edge: .leading).combined(with: .opacity)
                    ))

                Spacer(minLength: 0)
                navButtons
            }
            .padding(NowFocusSpace.s8)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(NowFocusColors.ground)
        }
        .frame(minWidth: 880, minHeight: 540)
        .background(NowFocusColors.ground)
        .onAppear { recorder.checkPermission() }
    }

    // MARK: - Brand Rail

    private var brandRail: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s3) {
            Text("NowFocus")
                .font(NowFocusFonts.heading(28))
                .foregroundColor(NowFocusColors.ink)

            Text("Your focus,\nyour rules.")
                .font(NowFocusFonts.heading(18))
                .foregroundColor(NowFocusColors.neutral700)
                .lineSpacing(4)

            Spacer(minLength: 0)

            Text("This takes\nabout 2 minutes.")
                .font(NowFocusFonts.body(12))
                .foregroundColor(NowFocusColors.neutral600)
        }
        .padding(NowFocusSpace.s8)
        .frame(width: 220)
        .background(NowFocusColors.neutral100)
    }

    // MARK: - Step indicator

    private var stepIndicator: some View {
        HStack(spacing: NowFocusSpace.s2) {
            ForEach(0..<totalSteps, id: \.self) { i in
                Circle()
                    .fill(i <= step ? NowFocusColors.accent : NowFocusColors.neutral300)
                    .frame(width: 7, height: 7)
                    .animation(.easeInOut(duration: 0.2), value: step)
            }
            Spacer()
        }
    }

    // MARK: - Step content router

    @ViewBuilder
    private var stepContent: some View {
        switch step {
        case 0: welcomeStep
        case 1: scheduleStep
        case 2: goalsStep
        case 3: connectionsStep
        case 4: voiceStep
        default: EmptyView()
        }
    }

    // MARK: - Navigation

    private var canAdvance: Bool {
        switch step {
        case 2: return !goals.isEmpty
        case 4: return recorder.recorderState == .recorded || recorder.recorderState == .playing
        default: return true
        }
    }

    private var nextLabel: String {
        step == totalSteps - 1 ? "Get Started →" : "Continue →"
    }

    private var navButtons: some View {
        HStack(spacing: NowFocusSpace.s3) {
            if step > 0 {
                NowFocusSecondaryButton(title: "← Back") {
                    withAnimation { step -= 1 }
                }
            }
            Spacer()

            // Skip — only on schedule (1) and connections (3) and voice (4 if denied)
            if step == 1 || step == 3 || (step == 4 && recorder.permissionStatus == .denied) {
                NowFocusGhostButton(title: "Skip") {
                    advanceOrComplete()
                }
            }

            NowFocusPrimaryButton(title: nextLabel, enabled: canAdvance) {
                saveCurrentStep()
                advanceOrComplete()
            }
        }
        .padding(.top, NowFocusSpace.s4)
    }

    private func advanceOrComplete() {
        withAnimation {
            if step < totalSteps - 1 {
                step += 1
            } else {
                finish()
            }
        }
    }

    private func finish() {
        onComplete()
    }

    // MARK: - Save per step

    private func saveCurrentStep() {
        switch step {
        case 1: saveSchedule()
        case 2: saveGoals()
        case 3: saveConnections()
        default: break
        }
    }

    private func saveSchedule() {
        var work = WorkSchedule()
        work.startMinute = dateToMinutes(workStart)
        work.endMinute   = dateToMinutes(workEnd)
        WorkScheduleStore.shared.schedule = work

        let sleepMin = dateToMinutes(sleepTime)
        let wakeMin  = dateToMinutes(wakeTime)
        var bedtime  = BedtimeSettingsStore.shared.settings
        bedtime.sleepMinute     = sleepMin
        bedtime.wakeMinute      = wakeMin
        bedtime.windDownMinute  = max(0, sleepMin - 60)
        bedtime.enabled         = false // user enables manually
        BedtimeSettingsStore.shared.settings = bedtime
    }

    private func saveGoals() {
        for goal in goals {
            try? DatabaseManager.shared.saveGoal(goal)
        }
    }

    private func saveConnections() {
        for conn in connections {
            try? DatabaseManager.shared.saveConnection(conn)
        }
    }

    // MARK: - Step 0: Welcome

    private var welcomeStep: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s4) {
            Text("What matters\nto you?")
                .font(NowFocusFonts.heading(36))
                .foregroundColor(NowFocusColors.ink)
                .lineSpacing(4)

            NowFocusRule(thick: true)

            Text("NowFocus will remind you of your goals and the people you care about when you're tempted to break focus or quit a session early.")
                .font(NowFocusFonts.body(15))
                .foregroundColor(NowFocusColors.neutral800)
                .fixedSize(horizontal: false, vertical: true)
                .lineSpacing(3)

            Text("We'll ask you three quick questions and record a short voice message — then you're done.")
                .font(NowFocusFonts.body(14))
                .foregroundColor(NowFocusColors.neutral700)
                .fixedSize(horizontal: false, vertical: true)
                .lineSpacing(3)
        }
    }

    // MARK: - Step 1: Schedule

    private var scheduleStep: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s6) {
            stepHeader(
                title: "When do you work\nand sleep?",
                body: "This helps NowFocus fit around your day. You can change these anytime."
            )

            VStack(alignment: .leading, spacing: NowFocusSpace.s3) {
                scheduleSection(label: "WORK HOURS") {
                    VStack(spacing: 0) {
                        timeRow("Start", binding: $workStart)
                        timeRow("End",   binding: $workEnd)
                    }
                }

                scheduleSection(label: "BEDTIME") {
                    VStack(spacing: 0) {
                        timeRow("Sleep at", binding: $sleepTime)
                        timeRow("Wake at",  binding: $wakeTime)
                    }
                }
            }

            Text("Bedtime Wind-Down is off by default — enable it in Preferences when you're ready.")
                .font(NowFocusFonts.body(12))
                .foregroundColor(NowFocusColors.neutral600)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func scheduleSection<Content: View>(label: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s2) {
            Text(label)
                .font(NowFocusFonts.body(11).weight(.semibold))
                .tracking(1.0)
                .foregroundColor(NowFocusColors.neutral700)
            NowFocusRule()
            content()
        }
    }

    private func timeRow(_ label: String, binding: Binding<Date>) -> some View {
        HStack {
            Text(label)
                .font(NowFocusFonts.body(14))
                .foregroundColor(NowFocusColors.ink)
            Spacer()
            DatePicker("", selection: binding, displayedComponents: .hourAndMinute)
                .labelsHidden()
        }
        .padding(.vertical, NowFocusSpace.s2)
        .overlay(alignment: .bottom) { NowFocusRule() }
    }

    // MARK: - Step 2: Goals

    private var goalsStep: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s4) {
            stepHeader(
                title: "What are you\nworking toward?",
                body: "NowFocus will show these when you're about to quit a session early."
            )

            // Add row
            VStack(alignment: .leading, spacing: NowFocusSpace.s2) {
                HStack(spacing: NowFocusSpace.s2) {
                    TextField("e.g. Finish my thesis", text: $newGoalText)
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

                if let err = goalError {
                    Text(err)
                        .font(NowFocusFonts.body(11))
                        .foregroundColor(NowFocusColors.accent700)
                }
            }

            // Goal list
            if !goals.isEmpty {
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
            }

            if goals.isEmpty {
                Text("Add at least one goal to continue.")
                    .font(NowFocusFonts.body(12))
                    .foregroundColor(NowFocusColors.accent700)
            }
        }
    }

    private func addGoal() {
        let text = newGoalText.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else {
            goalError = "Enter a goal first."
            return
        }
        goals.append(UserGoal(text: text, priority: newGoalPriority))
        newGoalText = ""
        goalError = nil
    }

    // MARK: - Step 3: Connections

    private var connectionsStep: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s4) {
            stepHeader(
                title: "Who would you rather\nconnect with?",
                body: "Instead of scrolling, NowFocus can remind you to call someone who matters."
            )

            // Add row
            VStack(alignment: .leading, spacing: NowFocusSpace.s2) {
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

            // Connection list
            if !connections.isEmpty {
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
            }
        }
    }

    private func addConnection() {
        let name = newConnName.trimmingCharacters(in: .whitespaces)
        guard !name.isEmpty else { return }
        let phone = newConnPhone.trimmingCharacters(in: .whitespaces)
        connections.append(UserConnection(
            name: name,
            phoneNumber: phone.isEmpty ? nil : phone
        ))
        newConnName = ""
        newConnPhone = ""
    }

    // MARK: - Step 4: Voice Message

    private var voiceStep: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s4) {
            stepHeader(
                title: "Record a message\nto your future self.",
                body: "When you're about to quit a session, you'll hear this. Make it count."
            )

            switch recorder.permissionStatus {
            case .denied:
                deniedPermissionNote

            case .unknown:
                NowFocusPrimaryButton(title: "Allow Microphone Access") {
                    recorder.requestPermission()
                }
                Text("NowFocus needs mic access to record. The file stays on this Mac.")
                    .font(NowFocusFonts.body(12))
                    .foregroundColor(NowFocusColors.neutral700)

            case .granted:
                voiceRecordingControls
            }
        }
    }

    private var voiceRecordingControls: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s4) {
            // Big record / stop button
            HStack(spacing: NowFocusSpace.s4) {
                Button {
                    switch recorder.recorderState {
                    case .idle, .recorded:
                        recorder.startRecording()
                    case .recording:
                        recorder.stopRecording()
                    case .playing:
                        recorder.stopPlayback()
                    }
                } label: {
                    ZStack {
                        Circle()
                            .fill(recorder.recorderState == .recording
                                  ? NowFocusColors.accent
                                  : NowFocusColors.neutral200)
                            .frame(width: 72, height: 72)
                            .scaleEffect(recorder.recorderState == .recording ? 1.05 : 1.0)
                            .animation(.easeInOut(duration: 0.6).repeatForever(autoreverses: true),
                                       value: recorder.recorderState == .recording)

                        Image(systemName: recorder.recorderState == .recording ? "stop.fill" : "mic.fill")
                            .font(.system(size: 26))
                            .foregroundColor(recorder.recorderState == .recording
                                             ? NowFocusColors.ground
                                             : NowFocusColors.ink)
                    }
                }
                .buttonStyle(.plain)

                VStack(alignment: .leading, spacing: NowFocusSpace.s1) {
                    switch recorder.recorderState {
                    case .idle:
                        Text("Tap to record")
                            .font(NowFocusFonts.body(14).weight(.semibold))
                            .foregroundColor(NowFocusColors.ink)
                        Text("Up to 60 seconds")
                            .font(NowFocusFonts.body(12))
                            .foregroundColor(NowFocusColors.neutral700)
                    case .recording:
                        Text(String(format: "Recording… %.0fs / 60s", recorder.recordingDuration))
                            .font(NowFocusFonts.body(14).weight(.semibold))
                            .foregroundColor(NowFocusColors.accent)
                        Text("Tap to stop")
                            .font(NowFocusFonts.body(12))
                            .foregroundColor(NowFocusColors.neutral700)
                    case .recorded:
                        Text("Recording saved ✓")
                            .font(NowFocusFonts.body(14).weight(.semibold))
                            .foregroundColor(NowFocusColors.ink)
                        Text(String(format: "%.0f seconds", recorder.recordingDuration))
                            .font(NowFocusFonts.body(12))
                            .foregroundColor(NowFocusColors.neutral700)
                    case .playing:
                        Text(String(format: "Playing… %.0fs", recorder.playbackProgress))
                            .font(NowFocusFonts.body(14).weight(.semibold))
                            .foregroundColor(NowFocusColors.accent)
                        Text("Tap to stop")
                            .font(NowFocusFonts.body(12))
                            .foregroundColor(NowFocusColors.neutral700)
                    }
                }
            }

            // Play / re-record row (only when a recording exists)
            if recorder.recorderState == .recorded || recorder.recorderState == .playing {
                HStack(spacing: NowFocusSpace.s3) {
                    NowFocusSecondaryButton(title: recorder.recorderState == .playing ? "■ Stop" : "▶ Play") {
                        if recorder.recorderState == .playing {
                            recorder.stopPlayback()
                        } else {
                            recorder.startPlayback()
                        }
                    }
                    NowFocusGhostButton(title: "Re-record") {
                        recorder.deleteRecording()
                    }
                }
            }
        }
    }

    private var deniedPermissionNote: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s3) {
            Text("Microphone access is required to record a voice message.")
                .font(NowFocusFonts.body(14))
                .foregroundColor(NowFocusColors.accent800)
                .fixedSize(horizontal: false, vertical: true)
            Text("You can enable it in System Settings → Privacy & Security → Microphone, then re-open NowFocus.")
                .font(NowFocusFonts.body(13))
                .foregroundColor(NowFocusColors.neutral700)
                .fixedSize(horizontal: false, vertical: true)
            NowFocusGhostButton(title: "Open Privacy Settings") {
                if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Microphone") {
                    NSWorkspace.shared.open(url)
                }
            }
            Text("You can also skip this step and add a voice message later from \"My Why\" in Preferences.")
                .font(NowFocusFonts.body(12))
                .foregroundColor(NowFocusColors.neutral600)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(NowFocusSpace.s3)
        .background(NowFocusColors.accent100)
    }

    // MARK: - Helpers

    private func stepHeader(title: String, body: String) -> some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s3) {
            Text(title)
                .font(NowFocusFonts.heading(30))
                .foregroundColor(NowFocusColors.ink)
                .lineSpacing(3)
            NowFocusRule(thick: true)
            Text(body)
                .font(NowFocusFonts.body(14))
                .foregroundColor(NowFocusColors.neutral800)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

// MARK: - Utilities

private func minutesToDate(_ minutes: Int) -> Date {
    Calendar.current.date(
        bySettingHour: minutes / 60,
        minute: minutes % 60,
        second: 0,
        of: Date()
    ) ?? Date()
}

private func dateToMinutes(_ date: Date) -> Int {
    let c = Calendar.current.dateComponents([.hour, .minute], from: date)
    return (c.hour ?? 0) * 60 + (c.minute ?? 0)
}
