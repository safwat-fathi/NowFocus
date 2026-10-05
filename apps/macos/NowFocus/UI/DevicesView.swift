import SwiftUI
import NowFocusCore

/// "This Mac" health (the three enforcement layers, reported separately per the
/// spec's Phase 3 requirement), then the optional account that syncs profiles
/// and bedtime settings across devices (see AccountView).
struct DevicesView: View {
    private enum LayerState {
        case checking, on, off, idle

        var label: String {
            switch self {
            case .checking: return String(localized: "Checking")
            case .on: return String(localized: "On")
            case .off: return String(localized: "Off")
            case .idle: return String(localized: "Idle")
            }
        }
    }

    @State private var daemonHealthy: Bool?
    @State private var appBlockingOk = true
    @State private var hostsFilterState: LayerState = .checking

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: NowFocusSpace.s4) {
                Text("Devices")
                    .font(NowFocusFonts.heading(28))
                    .foregroundColor(NowFocusColors.ink)

                NowFocusRule(thick: true)

                HStack {
                    Text("This Mac")
                        .font(NowFocusFonts.body(17).weight(.semibold))
                        .foregroundColor(NowFocusColors.ink)
                    Spacer()
                    NowFocusTagPill(text: overallDegraded ? "Degraded" : "Active", accent: overallDegraded)
                }

                NowFocusRule(thick: true)

                HStack(spacing: 0) {
                    layerCell("Root daemon", state: daemonLayerState)
                    NowFocusRule(vertical: true)
                    layerCell("DNS filter", state: hostsFilterState)
                    NowFocusRule(vertical: true)
                    layerCell("App blocking", state: appBlockingOk ? .on : .off)
                }

                NowFocusRule(thick: true)

                AccountView()
                    .padding(.top, NowFocusSpace.s4)
            }
            .padding(NowFocusSpace.s6)
            .frame(maxWidth: .infinity, alignment: .topLeading)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .onAppear(perform: refresh)
    }

    private var daemonLayerState: LayerState {
        guard let daemonHealthy else { return .checking }
        return daemonHealthy ? .on : .off
    }

    private var overallDegraded: Bool {
        daemonLayerState == .off || hostsFilterState == .off || !appBlockingOk
    }

    private func layerCell(_ label: LocalizedStringKey, state: LayerState) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(state.label.uppercased())
                .font(NowFocusFonts.body(12).weight(.semibold))
                .foregroundColor(state == .off ? NowFocusColors.accent700 : NowFocusColors.ink)
            Text(label)
                .font(NowFocusFonts.body(12))
                .foregroundColor(NowFocusColors.neutral700)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(NowFocusSpace.s2)
    }

    private func refresh() {
        appBlockingOk = AppBlocker.shared.checkHealth()

        DaemonClient.shared.checkHealth { healthy in
            DispatchQueue.main.async {
                daemonHealthy = healthy
                recomputeHostsFilterState()
            }
        }
    }

    /// A healthy daemon ping doesn't prove `/etc/hosts` actually holds the
    /// block it should — this reads the world-readable file directly and
    /// checks for the marker(s) a currently-expected session/commitment would
    /// have written, rather than inferring "On" from the ping alone.
    private func recomputeHostsFilterState() {
        let sessionExpected = (try? DatabaseManager.shared.fetchActiveSession()) != nil

        DaemonClient.shared.fetchCommitmentStatus { status in
            DispatchQueue.main.async {
                let commitmentExpected = status != nil
                guard sessionExpected || commitmentExpected else {
                    hostsFilterState = .idle
                    return
                }
                let hostsContent = (try? String(contentsOfFile: "/etc/hosts", encoding: .utf8)) ?? ""
                let sessionOk = !sessionExpected || hostsContent.contains(HostsFileMarkers.sessionStart)
                let commitmentOk = !commitmentExpected || hostsContent.contains(HostsFileMarkers.commitmentStart)
                hostsFilterState = (sessionOk && commitmentOk) ? .on : .off
            }
        }
    }
}
