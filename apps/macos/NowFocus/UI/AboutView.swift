import SwiftUI

struct AboutView: View {
    private static let links: [(String, String)] = [
        ("Website", "https://nowfocus.online/"),
        ("Privacy policy", "https://nowfocus.online/privacy/"),
        ("Terms", "https://nowfocus.online/terms/"),
        ("Source code", "https://github.com/safwat-fathi/NowFocus"),
        ("Contact support", "mailto:safwat.rashwan@gmail.com"),
    ]

    private var version: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
    }

    var body: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s3) {
            Text("About")
                .font(NowFocusFonts.heading(22))
                .foregroundColor(NowFocusColors.ink)

            NowFocusRule(thick: true)

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
        .padding(NowFocusSpace.s6)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}
