import SwiftUI
import NowFocusCore
import ServiceManagement

struct MenuBarView: View {
    @Environment(\.openSettings) private var openSettings

    @State private var isActive: Bool = false
    @State private var activeSession: FocusSession?
    @State private var healthStatus: EnforcementStatus = .unknown
    
    @State private var policies: [BlockPolicy] = []
    @State private var selectedPolicyId: String?
    @State private var selectedDuration: TimeInterval = 60 * 60 // 1 hour default
    
    private let durations: [(String, TimeInterval)] = [
        ("25 min", 25 * 60),
        ("45 min", 45 * 60),
        ("1 hour", 60 * 60),
        ("1.5 hours", 90 * 60),
        ("2 hours", 120 * 60),
        ("3 hours", 180 * 60),
        ("4 hours", 240 * 60)
    ]
    
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("NowFocus")
                .font(.headline)
            
            Divider()
            
            if isActive, let session = activeSession {
                HStack {
                    Circle()
                        .fill(.green)
                        .frame(width: 8, height: 8)
                    Text("Session Active")
                        .foregroundColor(.green)
                }
                
                Text(timerInterval: session.startAt...session.endAt, countsDown: true)
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .monospacedDigit()
                
                Button("Stop Session") {
                    stopSession()
                }
            } else {
                if policies.isEmpty {
                    Text("No policies configured")
                        .foregroundColor(.secondary)
                        .font(.caption)
                    Text("Open Preferences to create one.")
                        .foregroundColor(.secondary)
                        .font(.caption)
                } else {
                    // Policy picker
                    Picker("Policy", selection: $selectedPolicyId) {
                        ForEach(policies) { policy in
                            Text(policy.name).tag(Optional(policy.id))
                        }
                    }
                    .pickerStyle(.menu)
                    
                    // Duration picker
                    Picker("Duration", selection: $selectedDuration) {
                        ForEach(durations, id: \.1) { label, value in
                            Text(label).tag(value)
                        }
                    }
                    .pickerStyle(.menu)
                    
                    Button("Start Focus Session") {
                        startSession()
                    }
                    .disabled(selectedPolicyId == nil)
                }
            }
            
            Divider()
            
            HStack {
                Circle()
                    .fill(healthColor)
                    .frame(width: 6, height: 6)
                Text("Health: \(healthStatus.rawValue.capitalized)")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            registrationWarning

            Divider()
            
            Button("Preferences...") {
                NSApp.activate(ignoringOtherApps: true)
                openSettings()
            }
            
            Button("Quit") {
                NSApplication.shared.terminate(nil)
            }
        }
        .padding()
        .frame(width: 260)
        .onAppear {
            loadPolicies()
            refreshState()
        }
    }
    
    @ViewBuilder
    private var registrationWarning: some View {
        switch DaemonRegistrationStatus.shared.state {
        case .requiresApproval:
            VStack(alignment: .leading, spacing: 2) {
                Text("Website blocking needs approval")
                    .font(.caption)
                    .foregroundColor(.orange)
                Button("Open Login Items Settings") {
                    SMAppService.openSystemSettingsLoginItems()
                }
                .font(.caption)
            }
        case .failed:
            Text("Website blocking unavailable — daemon failed to register")
                .font(.caption)
                .foregroundColor(.red)
        case .unknown, .registered:
            EmptyView()
        }
    }

    private var healthColor: Color {
        switch healthStatus {
        case .active: return .green
        case .degraded: return .yellow
        case .unavailable, .unknown: return .red
        }
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
            deviceId: "local"
        )
        
        do {
            try DatabaseManager.shared.saveSession(session)
            SessionController.startEnforcement(policy: policy)
            refreshState()
        } catch {
            print("Failed to start session: \(error)")
        }
    }

    private func stopSession() {
        guard let session = activeSession else { return }
        SessionController.endSession(session)
        refreshState()
    }
}
