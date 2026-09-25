import SwiftUI
import Sparkle

struct PreferencesView: View {
    // Placeholder Sparkle controller
    private let updaterController = SPUStandardUpdaterController(startingUpdater: false, updaterDelegate: nil, userDriverDelegate: nil)

    private enum Tab {
        case policies, general
    }

    @State private var selectedTab: Tab = .policies

    var body: some View {
        VStack(spacing: 0) {
            Picker("", selection: $selectedTab) {
                Label("Policies", systemImage: "shield").tag(Tab.policies)
                Label("General", systemImage: "gear").tag(Tab.general)
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .padding()

            Divider()

            switch selectedTab {
            case .policies:
                PolicyListView()
            case .general:
                GeneralSettingsView(updaterController: updaterController)
            }
        }
        .frame(minWidth: 600, minHeight: 400)
    }
}

private struct GeneralSettingsView: View {
    let updaterController: SPUStandardUpdaterController

    var body: some View {
        VStack(spacing: 16) {
            Text("General Settings")
                .font(.title2)

            Button("Check for Updates...") {
                print("Updates not configured yet.")
            }
            .disabled(true)
            .padding()

            Text("Update server not configured yet.")
                .font(.caption)
                .foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
