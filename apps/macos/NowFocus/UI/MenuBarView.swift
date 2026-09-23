import SwiftUI
import NowFocusCore

struct MenuBarView: View {
    @State private var isActive: Bool = false
    @State private var activeSession: FocusSession?
    @State private var healthStatus: String = "Unknown"
    
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
                
                Text(timeRemaining(for: session))
                    .font(.caption)
                    .foregroundColor(.secondary)
                
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
                Text("Health: \(healthStatus)")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            
            Divider()
            
            Button("Preferences...") {
                NSApp.activate(ignoringOtherApps: true)
                if #available(macOS 13.0, *) {
                    NSApp.sendAction(Selector(("showSettingsWindow:")), to: nil, from: nil)
                } else {
                    NSApp.sendAction(Selector(("showPreferencesWindow:")), to: nil, from: nil)
                }
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
    
    private var healthColor: Color {
        switch healthStatus {
        case "Active": return .green
        case "Degraded": return .yellow
        default: return .red
        }
    }
    
    private func timeRemaining(for session: FocusSession) -> String {
        let remaining = session.endAt.timeIntervalSince(Date())
        guard remaining > 0 else { return "Ending..." }
        let minutes = Int(remaining) / 60
        let hours = minutes / 60
        let mins = minutes % 60
        if hours > 0 {
            return "\(hours)h \(mins)m remaining"
        } else {
            return "\(mins)m remaining"
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
                    try DatabaseManager.shared.saveSession(session)
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
                    self.healthStatus = "Active"
                } else if isHealthy || appHealth {
                    self.healthStatus = "Degraded"
                } else {
                    self.healthStatus = "Unavailable"
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
            
            DaemonClient.shared.apply(policy: policy)
            AppBlocker.shared.updatePolicy(
                isSessionActive: true,
                blockedApps: policy.applications.filter { $0.enabled }.map { $0.nativeIdentifier }
            )
            
            refreshState()
        } catch {
            print("Failed to start session: \(error)")
        }
    }
    
    private func stopSession() {
        guard var session = activeSession else { return }
        session.status = .completed
        
        do {
            try DatabaseManager.shared.saveSession(session)
            
            DaemonClient.shared.clear()
            AppBlocker.shared.updatePolicy(isSessionActive: false, blockedApps: [])
            
            refreshState()
        } catch {
            print("Failed to stop session: \(error)")
        }
    }
}
