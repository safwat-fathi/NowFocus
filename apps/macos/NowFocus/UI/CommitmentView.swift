import SwiftUI
import NowFocusCore
import AVFoundation

/// The 24/7 Always-Blocked lock — independent of focus sessions, owned and
/// enforced by the daemon (see CommitmentStore) so it survives quitting the
/// app. No curated category lists exist anywhere in this codebase, so this
/// supports user-added domains only for v1.
struct CommitmentView: View {
    @State private var daemonHealthy: Bool?
    @State private var status: CommitmentStatusDTO?
    @State private var newDomain = ""
    @State private var pendingDomains: [String] = []
    @State private var domainError: String?
    @State private var showConfirm = false
    @State private var actionError: String?

    var body: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s4) {
            Text("Commitment")
                .font(NowFocusFonts.heading(28))
                .foregroundColor(NowFocusColors.ink)
            Text("Always on, independent of focus sessions. Once started it runs the full 14 days — you get one 60-second window right after creating it to cancel; after that, not even quitting NowFocus lifts it.")
                .font(NowFocusFonts.body(13))
                .foregroundColor(NowFocusColors.neutral700)
                .fixedSize(horizontal: false, vertical: true)

            NowFocusRule(thick: true)

            switch daemonHealthy {
            case .none:
                Text("Checking enforcement…")
                    .font(NowFocusFonts.body(13))
                    .foregroundColor(NowFocusColors.neutral700)
            case .some(false):
                unavailableView
            case .some(true):
                if let status {
                    activeView(status)
                } else {
                    setupView
                }
            }

            if let actionError {
                Text(actionError)
                    .font(NowFocusFonts.body(12))
                    .foregroundColor(NowFocusColors.accent700)
            }

            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(NowFocusSpace.s6)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .onAppear(perform: checkHealthThenRefresh)
        .sheet(isPresented: $showConfirm) { confirmSheet }
    }

    @ViewBuilder
    private func activeView(_ status: CommitmentStatusDTO) -> some View {
        HStack {
            Text("Locked").font(NowFocusFonts.body(13)).foregroundColor(NowFocusColors.ink)
            Spacer()
            NowFocusTagPill(text: status.canCancelNow ? "Grace window" : "Active", accent: status.canCancelNow)
        }

        VStack(spacing: 0) {
            ForEach(status.domains, id: \.self) { domain in
                Text(domain)
                    .font(NowFocusFonts.body(14))
                    .foregroundColor(NowFocusColors.ink)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, NowFocusSpace.s2)
                    .overlay(alignment: .bottom) { NowFocusRule() }
            }
        }

        if Date() < status.endAt {
            Text(timerInterval: Date()...status.endAt, countsDown: true)
                .font(NowFocusFonts.heading(32))
                .foregroundColor(NowFocusColors.accent)
                .monospacedDigit()
        }

        if status.canCancelNow {
            NowFocusSecondaryButton(title: "Cancel — last chance") { cancel() }
        } else {
            Text("No early exit until this ends.")
                .font(NowFocusFonts.body(12))
                .foregroundColor(NowFocusColors.neutral700)
        }
    }

    private var setupView: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s3) {
            sectionLabel("Domains to lock")

            VStack(spacing: 0) {
                ForEach(pendingDomains, id: \.self) { domain in
                    HStack {
                        Text(domain)
                            .font(NowFocusFonts.body(14))
                            .foregroundColor(NowFocusColors.ink)
                        Spacer()
                        Button {
                            pendingDomains.removeAll { $0 == domain }
                        } label: {
                            Image(systemName: "trash").foregroundColor(NowFocusColors.accent700)
                        }
                        .buttonStyle(.plain)
                    }
                    .padding(.vertical, NowFocusSpace.s2)
                    .overlay(alignment: .bottom) { NowFocusRule() }
                }
            }

            HStack(spacing: NowFocusSpace.s2) {
                TextField("Add domain (e.g. reddit.com)", text: $newDomain)
                    .textFieldStyle(.plain)
                    .font(NowFocusFonts.body(14))
                    .foregroundColor(NowFocusColors.ink)
                    .padding(.vertical, NowFocusSpace.s2)
                    .overlay(NowFocusRule(), alignment: .bottom)
                    .onSubmit { addPendingDomain() }
                NowFocusSecondaryButton(title: "Add") { addPendingDomain() }
            }
            if let domainError {
                Text(domainError)
                    .font(NowFocusFonts.body(11))
                    .foregroundColor(NowFocusColors.accent700)
            }

            NowFocusPrimaryButton(title: "Start 14-Day Commitment", enabled: !pendingDomains.isEmpty) {
                showConfirm = true
            }
        }
    }

    private var confirmSheet: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s3) {
            Text("Lock these sites for 14 days?")
                .font(NowFocusFonts.heading(20))
                .foregroundColor(NowFocusColors.ink)
            Text("You'll have 60 seconds after starting to cancel. After that, there is no early exit — quitting NowFocus won't lift it either.")
                .font(NowFocusFonts.body(13))
                .foregroundColor(NowFocusColors.neutral800)
            VStack(alignment: .leading, spacing: 4) {
                ForEach(pendingDomains, id: \.self) { domain in
                    Text(domain).font(NowFocusFonts.body(13).weight(.semibold))
                }
            }

            HStack(spacing: NowFocusSpace.s2) {
                NowFocusSecondaryButton(title: "Cancel") { showConfirm = false }
                NowFocusPrimaryButton(title: "Start commitment") { start() }
            }
        }
        .padding(NowFocusSpace.s6)
        .frame(width: 360)
        .background(NowFocusColors.ground)
    }

    private func sectionLabel(_ text: LocalizedStringResource) -> some View {
        Text(locr(text).uppercased())
            .font(NowFocusFonts.body(11).weight(.semibold))
            .nfTracking(1.0)
            .foregroundColor(NowFocusColors.neutral700)
    }

    private func addPendingDomain() {
        guard let domain = DomainValidation.normalize(newDomain) else {
            domainError = loc("That doesn't look like a website. Try something like example.com")
            return
        }
        guard !pendingDomains.contains(domain) else {
            newDomain = ""
            return
        }
        pendingDomains.append(domain)
        newDomain = ""
        domainError = nil
    }

    private func start() {
        showConfirm = false
        DaemonClient.shared.applyCommitment(domains: pendingDomains) { success, message in
            DispatchQueue.main.async {
                if success {
                    pendingDomains = []
                    refresh()
                } else {
                    actionError = message ?? loc("Failed to start commitment.")
                }
            }
        }
    }

    private func cancel() {
        DaemonClient.shared.clearCommitment { success, message in
            DispatchQueue.main.async {
                if success {
                    refresh()
                } else {
                    actionError = message
                }
            }
        }
    }

    private func refresh() {
        DaemonClient.shared.fetchCommitmentStatus { fetched in
            DispatchQueue.main.async { status = fetched }
        }
    }

    /// The daemon is unreachable on a machine with the ad-hoc-signing issue
    /// Part A fixes — without this gate, the setup form would render as if
    /// everything's fine while Start silently did nothing.
    private func checkHealthThenRefresh() {
        DaemonClient.shared.checkHealth { healthy in
            DispatchQueue.main.async {
                daemonHealthy = healthy
                if healthy { refresh() }
            }
        }
    }

    private var unavailableView: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s1) {
            Text("Enforcement is degraded, so Commitment can't be started or checked right now.")
                .font(NowFocusFonts.body(13))
                .foregroundColor(NowFocusColors.accent800)
                .fixedSize(horizontal: false, vertical: true)
            Text("Fix the daemon connection (see the popover's Enforcement row), then reopen this screen.")
                .font(NowFocusFonts.body(12))
                .foregroundColor(NowFocusColors.neutral700)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(NowFocusSpace.s2)
        .background(NowFocusColors.accent100)
    }
}
