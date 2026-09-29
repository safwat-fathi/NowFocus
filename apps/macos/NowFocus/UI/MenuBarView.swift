import SwiftUI
import NowFocusCore
import ServiceManagement

struct MenuBarView: View {
    @Environment(\.openWindow) private var openWindow

    @State private var isActive: Bool = false
    @State private var activeSession: FocusSession?
    @State private var healthStatus: EnforcementStatus = .unknown

    @State private var policies: [BlockPolicy] = []
    @State private var selectedPolicyId: String?
    @State private var selectedDuration: TimeInterval = 60 * 60 // 1 hour default
    @State private var selectedMode: EnforcementMode = .normal

    @State private var showUnlockFlow = false

    private let durations: [(String, TimeInterval)] = [
        ("25m", 25 * 60),
        ("45m", 45 * 60),
        ("1h", 60 * 60),
        ("1.5h", 90 * 60),
        ("2h", 120 * 60),
        ("3h", 180 * 60),
        ("4h", 240 * 60)
    ]

    private let modes: [(String, EnforcementMode)] = [
        ("Normal", .normal),
        ("Strict", .strict),
        ("Locked", .locked)
    ]

    var body: some View {
        ZStack {
            VStack(alignment: .leading, spacing: 0) {
                header

                NowFocusRule(thick: true)
                    .padding(.vertical, NowFocusSpace.s3)

                if isActive, let session = activeSession {
                    runningSession(session)
                } else {
                    idleSession
                }

                NowFocusRule()
                    .padding(.top, NowFocusSpace.s3)

                enforcementStatus

                NowFocusRule()

                footer
            }
            .padding(NowFocusSpace.s4)

            if showUnlockFlow, let session = activeSession {
                UnlockOverlayView(
                    session: session,
                    onConfirmEnd: {
                        showUnlockFlow = false
                        stopSession()
                    },
                    onCancel: { showUnlockFlow = false }
                )
            }
        }
        .frame(width: 360)
        .background(NowFocusColors.ground)
        .onAppear {
            loadPolicies()
            refreshState()
        }
    }

    private var header: some View {
        HStack {
            Text("NowFocus")
                .font(NowFocusFonts.heading(17))
                .foregroundColor(NowFocusColors.ink)
            Spacer()
            NowFocusTagPill(text: isActive ? "Focusing" : "Ready", accent: isActive)
        }
    }

    @ViewBuilder
    private func runningSession(_ session: FocusSession) -> some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s2) {
            Text("\(policyName(for: session)) · \(session.enforcementMode.rawValue.capitalized)")
                .font(NowFocusFonts.body(13).weight(.semibold))
                .foregroundColor(NowFocusColors.ink)

            Text(timerInterval: session.startAt...session.endAt, countsDown: true)
                .font(NowFocusFonts.heading(40))
                .foregroundColor(NowFocusColors.accent)
                .monospacedDigit()
                .padding(.vertical, NowFocusSpace.s1)

            HStack(spacing: NowFocusSpace.s2) {
                NowFocusPrimaryButton(title: "Open") { openMainWindow() }
                NowFocusSecondaryButton(title: "End early") { attemptEndSession(session) }
            }
        }
        .padding(.bottom, NowFocusSpace.s3)
    }

    private var idleSession: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s3) {
            if policies.isEmpty {
                Text("No profiles configured")
                    .font(NowFocusFonts.body(12))
                    .foregroundColor(NowFocusColors.neutral700)
                Text("Open the window to create one.")
                    .font(NowFocusFonts.body(12))
                    .foregroundColor(NowFocusColors.neutral700)
            } else {
                sectionLabel("Profile")
                VStack(spacing: 0) {
                    ForEach(policies) { policy in
                        profileRow(policy)
                    }
                }

                sectionLabel("Duration")
                NowFocusSegmentedControl(options: durations.map { (label: $0.0, value: $0.1) }, selection: $selectedDuration)

                sectionLabel("Mode")
                NowFocusSegmentedControl(options: modes.map { (label: $0.0, value: $0.1) }, selection: $selectedMode)

                NowFocusPrimaryButton(title: "Start Focus Session", enabled: selectedPolicyId != nil) {
                    startSession()
                }
            }
        }
        .padding(.bottom, NowFocusSpace.s3)
    }

    private func profileRow(_ policy: BlockPolicy) -> some View {
        let isSelected = selectedPolicyId == policy.id
        return Button {
            selectedPolicyId = policy.id
        } label: {
            HStack(spacing: NowFocusSpace.s2) {
                ZStack {
                    Circle()
                        .strokeBorder(isSelected ? NowFocusColors.accent : NowFocusColors.neutral500, lineWidth: 2)
                    if isSelected {
                        Circle()
                            .fill(NowFocusColors.accent)
                            .padding(3)
                    }
                }
                .frame(width: 14, height: 14)

                Text(policy.name)
                    .font(NowFocusFonts.body(14).weight(.semibold))
                    .foregroundColor(NowFocusColors.ink)
                Spacer()
            }
            .padding(.vertical, NowFocusSpace.s2)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .overlay(alignment: .bottom) { NowFocusRule() }
    }

    private func sectionLabel(_ text: String) -> some View {
        Text(text.uppercased())
            .font(NowFocusFonts.body(11).weight(.semibold))
            .tracking(1.1)
            .foregroundColor(NowFocusColors.neutral700)
    }

    private var enforcementStatus: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s2) {
            HStack {
                Text("Enforcement")
                    .font(NowFocusFonts.body(13))
                    .foregroundColor(NowFocusColors.ink)
                Spacer()
                NowFocusTagPill(text: enforcementTagText, accent: healthStatus != .active)
            }

            if let fix = enforcementFix {
                VStack(alignment: .leading, spacing: NowFocusSpace.s1) {
                    Text(fix.message)
                        .font(NowFocusFonts.body(12))
                        .foregroundColor(NowFocusColors.accent800)
                        .fixedSize(horizontal: false, vertical: true)
                    if let action = fix.action {
                        NowFocusGhostButton(title: action.title, action: action.perform)
                    }
                }
                .padding(NowFocusSpace.s2)
                .background(NowFocusColors.accent100)
            }
        }
        .padding(.vertical, NowFocusSpace.s3)
    }

    private var enforcementTagText: String {
        switch healthStatus {
        case .unknown: return "Checking"
        case .active: return "Active"
        case .degraded: return "Degraded"
        case .unavailable: return "Unavailable"
        }
    }

    private struct EnforcementFix {
        let message: String
        let action: (title: String, perform: () -> Void)?
    }

    /// One fix box, not two — folds the old separate `registrationWarning`
    /// switch into the same status treatment as the health tag above it.
    private var enforcementFix: EnforcementFix? {
        switch DaemonRegistrationStatus.shared.state {
        case .requiresApproval:
            return EnforcementFix(
                message: "Website blocking needs approval.",
                action: (title: "Open Login Items Settings", perform: { SMAppService.openSystemSettingsLoginItems() })
            )
        case .failed(let message):
            return EnforcementFix(message: "Website blocking unavailable: \(message)", action: nil)
        case .unknown, .registered:
            guard healthStatus == .degraded || healthStatus == .unavailable else { return nil }
            return EnforcementFix(message: "One or more protection layers aren't responding.", action: nil)
        }
    }

    private var footer: some View {
        VStack(spacing: 2) {
            MenuItemView(
                title: "Preferences...",
                systemImage: "gearshape",
                action: { openMainWindow() }
            )

            MenuItemView(
                title: "Quit NowFocus",
                systemImage: "power",
                action: { NSApplication.shared.terminate(nil) },
                disabled: isActive
            )
        }
        .padding(.top, NowFocusSpace.s2)
    }

    private struct MenuItemView: View {
        let title: String
        let systemImage: String
        let action: () -> Void
        var disabled: Bool = false

        @State private var isHovered = false

        var body: some View {
            Button(action: action) {
                HStack(spacing: NowFocusSpace.s2) {
                    Image(systemName: systemImage)
                        .font(.system(size: 13))
                        .frame(width: 16, alignment: .center)
                    Text(title)
                        .font(NowFocusFonts.body(13))
                    Spacer()
                }
                .padding(.vertical, 6)
                .padding(.horizontal, NowFocusSpace.s2)
                .contentShape(Rectangle())
                .background(isHovered && !disabled ? NowFocusColors.neutral200 : Color.clear)
                .cornerRadius(4)
                .foregroundColor(disabled ? NowFocusColors.neutral500 : NowFocusColors.ink)
            }
            .buttonStyle(.plain)
            .disabled(disabled)
            .onHover { hovering in
                isHovered = hovering
            }
        }
    }

    private func openMainWindow() {
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
        openWindow(id: MainWindowView.windowID)
    }

    private func policyName(for session: FocusSession) -> String {
        policies.first(where: { $0.id == session.policyId })?.name ?? "Focus"
    }

    private func loadPolicies() {
        do {
            policies = try DatabaseManager.shared.fetchAllPolicies()
            if selectedPolicyId == nil, let first = policies.first {
                selectedPolicyId = first.id
            }
        } catch {
            print("Failed to load policies: \(error)")
        }
    }

    private func refreshState() {
        do {
            if var session = try DatabaseManager.shared.fetchActiveSession() {
                let engine = SessionEngine()
                engine.evaluateState(for: &session)
                if engine.isActive(session) {
                    isActive = true
                    activeSession = session
                } else {
                    isActive = false
                    activeSession = nil
                    SessionController.endSession(session)
                }
            } else {
                isActive = false
                activeSession = nil
            }
        } catch {
            print("Failed to fetch active session: \(error)")
        }

        DaemonClient.shared.checkHealth { isHealthy in
            DispatchQueue.main.async {
                let appHealth = AppBlocker.shared.checkHealth()
                if isHealthy && appHealth {
                    self.healthStatus = .active
                } else if isHealthy || appHealth {
                    self.healthStatus = .degraded
                } else {
                    self.healthStatus = .unavailable
                }
            }
        }
    }

    private func startSession() {
        guard let policyId = selectedPolicyId,
              let policy = policies.first(where: { $0.id == policyId }) else { return }

        let session = FocusSession(
            policyId: policy.id,
            startAt: Date(),
            endAt: Date().addingTimeInterval(selectedDuration),
            enforcementMode: selectedMode,
            deviceId: "local"
        )

        do {
            try DatabaseManager.shared.saveSession(session)
            SessionController.startEnforcement(policy: policy, sessionId: session.id)
            refreshState()
        } catch {
            print("Failed to start session: \(error)")
        }
    }

    /// Routes through `SessionEngine.stopGate` — Normal ends immediately,
    /// Strict/Locked present `UnlockOverlayView` instead of ending here.
    private func attemptEndSession(_ session: FocusSession) {
        switch SessionEngine().stopGate(for: session) {
        case .immediate:
            stopSession()
        case .requiresUnlock, .locked:
            showUnlockFlow = true
        }
    }

    private func stopSession() {
        guard let session = activeSession else { return }
        SessionController.endSession(session, cancelled: true)
        refreshState()
    }
}
