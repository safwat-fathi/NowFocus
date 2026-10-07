import SwiftUI
import Sparkle
import NowFocusCore

/// App-wide settings: the language, updates, and About (version, links, license).
struct SettingsView: View {
    let updaterController: SPUStandardUpdaterController

    /// What the segmented control shows. The change only takes effect after a relaunch, so it is held here
    /// until "Restart now"; leaving the screen drops it.
    @State private var choice = AppLanguage.choice

    private static let links: [(LocalizedStringKey, String)] = [
        ("Website", "https://nowfocus.online/"),
        ("Privacy policy", "https://nowfocus.online/privacy/"),
        ("Terms", "https://nowfocus.online/terms/"),
        ("Source code", "https://github.com/safwat-fathi/NowFocus"),
        ("Contact support", "mailto:safwat.rashwan@gmail.com"),
    ]

    @State private var reportMessage = ""
    @State private var reportContact = ""
    @State private var reportBusy = false
    @State private var reportResult: String?
    @State private var reportSent = false

    private var version: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
    }

    private var sessionActive: Bool { SessionController.status.isActive }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: NowFocusSpace.s3) {
                Text("Settings")
                    .font(NowFocusFonts.heading(22))
                    .foregroundColor(NowFocusColors.ink)

                NowFocusRule(thick: true)

                language

                NowFocusRule()
                    .padding(.vertical, NowFocusSpace.s2)

                NowFocusSecondaryButton(title: "Check for Updates…") {
                    print("Updates not configured yet.")
                }
                Text("Update server not configured yet.")
                    .font(NowFocusFonts.body(12))
                    .foregroundColor(NowFocusColors.neutral700)

                NowFocusRule()
                    .padding(.vertical, NowFocusSpace.s2)

                report

                NowFocusRule()
                    .padding(.vertical, NowFocusSpace.s2)

                about
            }
            .padding(NowFocusSpace.s6)
            .frame(maxWidth: .infinity, alignment: .topLeading)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var language: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s2) {
            Text(loc("Language").uppercased())
                .font(NowFocusFonts.body(11).weight(.semibold))
                .nfTracking(1.0)
                .foregroundColor(NowFocusColors.neutral700)

            NowFocusSegmentedControl(
                options: [
                    (label: "System default", value: AppLanguageChoice.system),
                    // Each language names itself in its own script, so it can be found from the wrong one.
                    (label: LocalizedStringKey("English"), value: AppLanguageChoice.en),
                    (label: LocalizedStringKey("العربية"), value: AppLanguageChoice.ar),
                ],
                selection: $choice
            )
            .frame(maxWidth: 420)

            if choice != AppLanguage.choice {
                Text("NowFocus restarts to switch language.")
                    .font(NowFocusFonts.body(12))
                    .foregroundColor(NowFocusColors.neutral700)
                if sessionActive {
                    // Quitting would drop app blocking, and the app refuses to quit mid-session on purpose.
                    Text("End your focus session first, then restart.")
                        .font(NowFocusFonts.body(12))
                        .foregroundColor(NowFocusColors.accent800)
                } else {
                    NowFocusPrimaryButton(title: "Restart now") {
                        AppLanguage.set(choice)
                        AppLanguage.relaunch()
                    }
                    .frame(maxWidth: 220)
                }
            }
        }
    }

    private var report: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s2) {
            Text(loc("Report an issue").uppercased())
                .font(NowFocusFonts.body(11).weight(.semibold))
                .nfTracking(1.0)
                .foregroundColor(NowFocusColors.neutral700)
            Text("We get the app version and your macOS version, nothing else.")
                .font(NowFocusFonts.body(12))
                .foregroundColor(NowFocusColors.neutral700)
            TextField(loc("What went wrong?"), text: $reportMessage, axis: .vertical)
                .lineLimit(4...8)
                .modifier(FieldStyle())
                .frame(maxWidth: 420)
                .onChange(of: reportMessage) { _, new in
                    reportSent = false
                    if new.count > 4000 { reportMessage = String(new.prefix(4000)) }
                }
            TextField(loc("Email, if you want a reply (optional)"), text: $reportContact)
                .modifier(FieldStyle())
                .frame(maxWidth: 420)
            NowFocusPrimaryButton(title: reportBusy ? "Sending…" : "Send") {
                reportBusy = true; reportResult = nil
                Task {
                    reportResult = await SyncController.shared.reportIssue(message: reportMessage, contact: reportContact)
                    reportBusy = false
                    if reportResult == nil { reportMessage = ""; reportContact = ""; reportSent = true }
                }
            }
            .disabled(reportBusy || reportMessage.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            .frame(maxWidth: 220)
            if reportSent {
                Text("Thanks, your report was sent.")
                    .font(NowFocusFonts.body(12))
                    .foregroundColor(NowFocusColors.neutral700)
            }
            if let e = reportResult {
                Text(e)
                    .font(NowFocusFonts.body(12))
                    .foregroundColor(NowFocusColors.accent700)
            }
        }
    }

    private var about: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s3) {
            Text("About")
                .font(NowFocusFonts.body(11).weight(.semibold))
                .nfTracking(1.0)
                .foregroundColor(NowFocusColors.neutral700)

            Text("NowFocus")
                .font(NowFocusFonts.heading(28))
                .foregroundColor(NowFocusColors.ink)
            Text("Version \(version)")
                .font(NowFocusFonts.body(14))
                .foregroundColor(NowFocusColors.neutral700)
            Text("Blocks the apps that pull you away, and keeps them blocked until you're done.")
                .font(NowFocusFonts.body(14))
                .foregroundColor(NowFocusColors.ink)

            ForEach(Self.links, id: \.1) { label, url in
                if let u = URL(string: url) {
                    Link(label, destination: u)
                        .font(NowFocusFonts.body(14).weight(.semibold))
                }
            }

            Text("Source available under the FSL-1.1-ALv2 license.\n© 2026 Safwat Fathi")
                .font(NowFocusFonts.body(12))
                .foregroundColor(NowFocusColors.neutral700)
        }
    }
}
