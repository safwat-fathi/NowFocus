import SwiftUI
import Sparkle

struct PreferencesView: View {
    // Placeholder Sparkle controller
    private let updaterController = SPUStandardUpdaterController(startingUpdater: false, updaterDelegate: nil, userDriverDelegate: nil)
    
    var body: some View {
        TabView {
            PolicyListView()
                .tabItem {
                    Label("Policies", systemImage: "shield")
                }
            
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
            .tabItem {
                Label("General", systemImage: "gear")
            }
        }
        .frame(minWidth: 600, minHeight: 400)
    }
}
