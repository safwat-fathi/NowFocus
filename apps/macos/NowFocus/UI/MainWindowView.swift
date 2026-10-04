import SwiftUI
import Sparkle

/// Replaces the old `Settings` scene's segmented Policies/General tab
/// switcher. `NavigationSplitView`/`.listStyle(.sidebar)` draw their own
/// rounded, translucent chrome that can't be restyled flat — this is a plain
/// `HStack` + custom nav rows instead, modeled directly on the design's PC
/// sidebar (fixed-width rail, left accent bar on the selected row, an
/// "Always on" group for the always-running features).
struct MainWindowView: View {
    static let windowID = "main"

    @AppStorage("hasCompletedOnboarding") private var hasCompletedOnboarding = false

    enum Section: String, CaseIterable, Identifiable {
        case profiles = "Profiles"
        case devices = "Devices"
        case stats = "Stats"
        case commitment = "Commitment Shield"
        case bedtime = "Bedtime Wind-Down"
        case people = "People who matter"
        case goals = "Your goals"
        case general = "General"
        case about = "About"

        var id: String { rawValue }
    }

    @State private var selection: Section = .profiles
    private let updaterController = SPUStandardUpdaterController(startingUpdater: false, updaterDelegate: nil, userDriverDelegate: nil)

    var body: some View {
        if hasCompletedOnboarding {
            mainLayout
        } else {
            OnboardingView {
                hasCompletedOnboarding = true
            }
        }
    }

    @ViewBuilder
    private var mainLayout: some View {
        HStack(spacing: 0) {
            sidebar
            NowFocusRule(vertical: true)
            detail
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .frame(minWidth: 880, minHeight: 480)
        .background(NowFocusColors.ground)
    }

    private var sidebar: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("NowFocus")
                .font(NowFocusFonts.heading(22))
                .foregroundColor(NowFocusColors.ink)
                .padding(.horizontal, NowFocusSpace.s4)
                .padding(.top, NowFocusSpace.s4)
                .padding(.bottom, NowFocusSpace.s3)

            NowFocusRule()

            navRow(.profiles, systemImage: "square.stack")
            navRow(.devices, systemImage: "laptopcomputer")
            navRow(.stats, systemImage: "chart.bar")

            Text("PROTECTIONS")
                .font(NowFocusFonts.body(11).weight(.semibold))
                .tracking(1.0)
                .foregroundColor(NowFocusColors.neutral700)
                .padding(.horizontal, NowFocusSpace.s4)
                .padding(.top, NowFocusSpace.s6)
                .padding(.bottom, NowFocusSpace.s2)

            NowFocusRule()

            navRow(.commitment, systemImage: "lock.shield")
            navRow(.bedtime, systemImage: "moon.stars")
            navRow(.people, systemImage: "person.2")
            navRow(.goals, systemImage: "target")

            Spacer(minLength: 0)

            NowFocusRule()
            navRow(.general, systemImage: "gearshape")
            navRow(.about, systemImage: "info.circle")
        }
        .frame(width: 220)
        .background(NowFocusColors.ground)
    }

    private func navRow(_ section: Section, systemImage: String) -> some View {
        let isSelected = selection == section
        return Button {
            selection = section
        } label: {
            HStack(spacing: NowFocusSpace.s2) {
                Image(systemName: systemImage)
                    .frame(width: 18)
                Text(section.rawValue)
                    .font(NowFocusFonts.body(14).weight(.semibold))
                Spacer()
            }
            .foregroundColor(NowFocusColors.ink)
            .padding(.leading, NowFocusSpace.s3)
            .padding(.trailing, NowFocusSpace.s4)
            .padding(.vertical, NowFocusSpace.s2)
            .background(isSelected ? NowFocusColors.neutral200 : Color.clear)
            .overlay(alignment: .leading) {
                Rectangle()
                    .fill(isSelected ? NowFocusColors.accent : Color.clear)
                    .frame(width: 3)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private var detail: some View {
        switch selection {
        case .profiles:   PolicyListView()
        case .devices:    DevicesView()
        case .stats:      StatsView()
        case .commitment: CommitmentView()
        case .bedtime:    BedtimeView()
        case .people:     PeopleView()
        case .goals:      GoalsView()
        case .general:    GeneralSettingsView(updaterController: updaterController)
        case .about:      AboutView()
        }
    }
}

struct GeneralSettingsView: View {
    let updaterController: SPUStandardUpdaterController

    var body: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s3) {
            Text("General")
                .font(NowFocusFonts.heading(22))
                .foregroundColor(NowFocusColors.ink)

            NowFocusRule(thick: true)

            NowFocusSecondaryButton(title: "Check for Updates…") {
                print("Updates not configured yet.")
            }

            Text("Update server not configured yet.")
                .font(NowFocusFonts.body(12))
                .foregroundColor(NowFocusColors.neutral700)
        }
        .padding(NowFocusSpace.s6)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}
