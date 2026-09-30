import SwiftUI

struct RootView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.scenePhase) private var phase

    private var tabsVisible: Bool {
        switch model.screen {
        case .home, .policies, .editPolicy, .stats, .devices, .active: return true
        default: return false
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            Group {
                switch model.screen {
                case .home: HomeView()
                case .setup: SetupView()
                case .active: ActiveView()
                case .unlock: UnlockView()
                case .policies: PoliciesView()
                case .editPolicy(let id): PolicyEditorView(policyId: id)
                case .stats: StatsView()
                case .commitment: CommitmentView()
                case .bedtime: BedtimeView()
                case .devices: DevicesView()
                case .onboarding: OnboardingView()
                case .people: PeopleView()
                case .goals: GoalsView()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            if tabsVisible {
                NowFocusRule()
                BottomTabBar()
            }
        }
        .foregroundColor(NowFocusColors.ink)
        .background(NowFocusColors.ground.ignoresSafeArea())
        .task {
            while !Task.isCancelled {
                model.tick()
                try? await Task.sleep(for: .seconds(1))
            }
        }
        .onChange(of: phase) { _, new in if new == .active { model.refresh() } }
    }
}
