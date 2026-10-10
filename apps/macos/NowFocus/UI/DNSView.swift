import SwiftUI
import NowFocusCore

/// The DNS this Mac uses while NowFocus runs, and whether it also runs outside focus sessions. Top to bottom: is it
/// working (a status and one sentence), which server, when. The decisions are `DNSRules`'s (NowFocusCore); the
/// daemon owns what is applied (DNSEnforcer), this screen only states the user's intent (DNSController).
struct DNSView: View {
    private var session: SessionStatus { SessionController.status }
    @State private var choice = DNSChoice.load()
    @State private var customText = DNSChoice.load().custom
    @State private var reachable: Bool?
    @State private var applied: DNSState?
    @State private var customError = false
    private let refreshTimer = Timer.publish(every: 5, on: .main, in: .common).autoconnect()

    private static let servers: [(DNSProvider, LocalizedStringKey, LocalizedStringKey)] = [
        (.system, "My network's DNS", "NowFocus changes nothing."),
        (.adguardFamily, "AdGuard Family", "Blocks adult sites, ads and malware"),
        (.cloudflareFamily, "Cloudflare Family", "Blocks adult sites and malware"),
        (.cleanbrowsingFamily, "CleanBrowsing Family", "Blocks adult sites"),
        (.quad9, "Quad9", "Blocks malware"),
        (.custom, "Other server", "One to four addresses you type"),
    ]

    private var status: String {
        DNSRules.status(expected: choice.servers, alwaysOn: choice.alwaysOn, sessionLive: session.isActive, serviceUp: reachable ?? false, applied: applied)
    }

    private var providerName: String {
        switch choice.provider {
        case .system: return loc("My network's DNS")
        case .adguardFamily: return loc("AdGuard Family")
        case .cloudflareFamily: return loc("Cloudflare Family")
        case .cleanbrowsingFamily: return loc("CleanBrowsing Family")
        case .quad9: return loc("Quad9")
        case .custom: return loc("Other server")
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: NowFocusSpace.s4) {
                Text("DNS")
                    .font(NowFocusFonts.heading(28))
                    .foregroundColor(NowFocusColors.ink)
                Text("Choose the server NowFocus uses for lookups. Your blocklist always applies first.")
                    .font(NowFocusFonts.body(13))
                    .foregroundColor(NowFocusColors.neutral700)
                    .fixedSize(horizontal: false, vertical: true)

                NowFocusRule(thick: true)

                if reachable == nil {
                    Text("Checking enforcement…")
                        .font(NowFocusFonts.body(13))
                        .foregroundColor(NowFocusColors.neutral700)
                } else {
                    statusBlock
                }

                NowFocusRule()
                serverList

                NowFocusRule()
                whenSection

                Text("Browsers with their own secure DNS ignore this. Ad and adult filtering is done by the server you pick, not by NowFocus.")
                    .font(NowFocusFonts.body(12))
                    .foregroundColor(NowFocusColors.neutral700)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(NowFocusSpace.s6)
            .frame(maxWidth: .infinity, alignment: .topLeading)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .onAppear {
            DNSController.reconcile()
            refresh()
        }
        .onReceive(refreshTimer) { _ in refresh() }
    }

    private var statusBlock: some View {
        let attention = status == "notApplied" || status == "unavailable"
        return VStack(alignment: .leading, spacing: NowFocusSpace.s2) {
            NowFocusTagPill(text: statusLabel, accent: attention)
            Text(verbatim: statusSentence)
                .font(NowFocusFonts.body(14))
                .foregroundColor(NowFocusColors.ink)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var statusLabel: LocalizedStringKey {
        switch status {
        case "waiting": return "Waiting"
        case "active": return "Active"
        case "notApplied": return "Not applied"
        case "unavailable": return "Helper not running"
        default: return "Off"
        }
    }

    private var statusSentence: String {
        switch status {
        case "waiting": return String(format: loc("%@ switches on when a focus session starts."), providerName)
        case "active": return String(format: loc("This Mac asks %@."), providerName)
        case "notApplied": return String(format: loc("%@ is chosen but not applied yet. NowFocus retries every couple of minutes. A network that uses an encrypted-DNS profile can't be changed."), providerName)
        case "unavailable": return loc("The NowFocus helper is not answering, so the DNS cannot be changed.")
        default: return loc("NowFocus is not changing this Mac's DNS.")
        }
    }

    private var serverList: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s2) {
            sectionLabel("Server")
            ForEach(Self.servers, id: \.0) { provider, title, sub in
                Button {
                    choice.provider = provider
                    save()
                } label: {
                    HStack(alignment: .top, spacing: NowFocusSpace.s3) {
                        Image(systemName: choice.provider == provider ? "circle.inset.filled" : "circle")
                            .foregroundColor(NowFocusColors.ink)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(title).font(NowFocusFonts.body(14).weight(.semibold)).foregroundColor(NowFocusColors.ink)
                            Text(sub).font(NowFocusFonts.body(12)).foregroundColor(NowFocusColors.neutral700)
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(.vertical, NowFocusSpace.s1)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            if choice.provider == .custom {
                TextField(loc("Server addresses, like 192.168.1.2, 1.1.1.1"), text: $customText)
                    .modifier(FieldStyle())
                    .frame(maxWidth: 420)
                    .onChange(of: customText) { _, new in
                        choice.custom = new
                        customError = DNSResolvers.parse(new) == nil
                        save()
                    }
                if customError {
                    Text("Enter one to four DNS server addresses, like 94.140.14.15.")
                        .font(NowFocusFonts.body(12))
                        .foregroundColor(NowFocusColors.accent700)
                }
            }
        }
    }

    private var whenSection: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s2) {
            sectionLabel("When it runs")
            NowFocusSegmentedControl(
                options: [
                    (label: LocalizedStringKey("Only during sessions"), value: false),
                    (label: LocalizedStringKey("Always on"), value: true),
                ],
                selection: Binding(get: { choice.alwaysOn }, set: { choice.alwaysOn = $0; save() })
            )
            .frame(maxWidth: 420)
            Text(choice.alwaysOn
                 ? "NowFocus keeps this Mac on this DNS until you switch back, and restores your original DNS when you do."
                 : "The server is used while a focus session is running; your own DNS comes back afterwards.")
                .font(NowFocusFonts.body(12))
                .foregroundColor(NowFocusColors.neutral700)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func sectionLabel(_ text: LocalizedStringResource) -> some View {
        Text(locr(text).uppercased())
            .font(NowFocusFonts.body(11).weight(.semibold))
            .nfTracking(1.0)
            .foregroundColor(NowFocusColors.neutral700)
    }

    /// An invalid custom list is stored as typed but applies nothing (`DNSChoice.servers` is empty for it).
    private func save() {
        DNSController.apply(choice)
        refresh()
    }

    private func refresh() {
        DaemonClient.shared.fetchDNSState { up, state in
            DispatchQueue.main.async {
                reachable = up
                applied = state
            }
        }
    }
}
