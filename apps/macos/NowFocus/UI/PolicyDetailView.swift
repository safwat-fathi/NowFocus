import SwiftUI
import NowFocusCore
import AppKit

/// Plain stacks instead of `Form.formStyle(.grouped)` — the grouped form
/// draws its own rounded, inset-grouped background that the flat/0-radius
/// design has no equivalent for.
struct PolicyDetailView: View {
    @Binding var policy: BlockPolicy
    let onSave: (BlockPolicy) -> Void

    @State private var newDomain: String = ""
    @FocusState private var nameFieldFocused: Bool

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: NowFocusSpace.s6) {
                section("Profile Name") {
                    TextField("Name", text: $policy.name)
                        .textFieldStyle(.plain)
                        .font(NowFocusFonts.body(16).weight(.semibold))
                        .foregroundColor(NowFocusColors.ink)
                        .padding(.vertical, NowFocusSpace.s2)
                        .overlay(NowFocusRule(), alignment: .bottom)
                        .focused($nameFieldFocused)
                        .onSubmit { save() }
                        .onChange(of: nameFieldFocused) { _, isFocused in
                            if !isFocused { save() }
                        }
                }

                section("Blocked Websites") {
                    VStack(spacing: 0) {
                        ForEach(policy.domains) { domain in
                            HStack {
                                Image(systemName: "globe")
                                    .foregroundColor(NowFocusColors.neutral600)
                                Text(domain.domain)
                                    .font(NowFocusFonts.body(14))
                                    .foregroundColor(NowFocusColors.ink)
                                Spacer()
                                if domain.includeSubdomains {
                                    // The hosts file lists exactly these variants (see NetworkEnforcer), not every subdomain.
                    Text("+ www, m, mobile")
                                        .font(NowFocusFonts.body(12))
                                        .foregroundColor(NowFocusColors.neutral700)
                                }
                                Button {
                                    policy.domains.removeAll { $0.id == domain.id }
                                    save()
                                } label: {
                                    Image(systemName: "trash")
                                        .foregroundColor(NowFocusColors.accent700)
                                }
                                .buttonStyle(.plain)
                            }
                            .padding(.vertical, NowFocusSpace.s2)
                            .overlay(alignment: .bottom) { NowFocusRule() }
                        }
                    }

                    HStack(spacing: NowFocusSpace.s2) {
                        TextField("Add domain (e.g. youtube.com)", text: $newDomain)
                            .textFieldStyle(.plain)
                            .font(NowFocusFonts.body(14))
                            .foregroundColor(NowFocusColors.ink)
                            .padding(.vertical, NowFocusSpace.s2)
                            .overlay(NowFocusRule(), alignment: .bottom)
                            .onSubmit { addDomain() }
                        NowFocusSecondaryButton(title: "Add") { addDomain() }
                    }
                    .padding(.top, NowFocusSpace.s2)
                }

                section("Blocked Applications") {
                    VStack(spacing: 0) {
                        ForEach(policy.applications) { app in
                            HStack {
                                Image(systemName: "app.fill")
                                    .foregroundColor(NowFocusColors.neutral600)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(app.displayName)
                                        .font(NowFocusFonts.body(14))
                                        .foregroundColor(NowFocusColors.ink)
                                    Text(app.nativeIdentifier)
                                        .font(NowFocusFonts.body(12))
                                        .foregroundColor(NowFocusColors.neutral700)
                                }
                                Spacer()
                                Button {
                                    policy.applications.removeAll { $0.id == app.id }
                                    save()
                                } label: {
                                    Image(systemName: "trash")
                                        .foregroundColor(NowFocusColors.accent700)
                                }
                                .buttonStyle(.plain)
                            }
                            .padding(.vertical, NowFocusSpace.s2)
                            .overlay(alignment: .bottom) { NowFocusRule() }
                        }
                    }

                    NowFocusSecondaryButton(title: "Add Application…") { pickApplication() }
                        .padding(.top, NowFocusSpace.s2)
                }

                section("Feeds only · Android") {
                    Text("Keep an app, lose its feed. Android only for now; on a Mac this needs a browser extension, which isn't available yet. Nothing here is switched on or enforced.")
                        .font(NowFocusFonts.body(12))
                        .foregroundColor(NowFocusColors.neutral700)
                        .fixedSize(horizontal: false, vertical: true)
                    VStack(spacing: 0) {
                        ForEach(FeedRules.all) { rule in
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(rule.label)
                                        .font(NowFocusFonts.body(14))
                                    Text(rule.detail)
                                        .font(NowFocusFonts.body(12))
                                        .foregroundColor(NowFocusColors.neutral700)
                                }
                                Spacer()
                                Toggle("", isOn: .constant(false))
                                    .labelsHidden()
                                    .toggleStyle(.switch)
                                    .disabled(true)
                            }
                            .foregroundColor(NowFocusColors.neutral600)
                            .padding(.vertical, NowFocusSpace.s2)
                            .overlay(alignment: .bottom) { NowFocusRule() }
                        }
                    }
                }
            }
            .padding(NowFocusSpace.s6)
        }
    }

    private func section(_ title: String, @ViewBuilder content: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s2) {
            Text(title.uppercased())
                .font(NowFocusFonts.body(11).weight(.semibold))
                .tracking(1.0)
                .lineLimit(1)
                .foregroundColor(NowFocusColors.neutral700)
            content()
        }
    }

    private func addDomain() {
        guard let domain = DomainValidation.normalize(newDomain) else { return }
        guard !policy.domains.contains(where: { $0.domain == domain }) else {
            newDomain = ""
            return
        }

        policy.domains.append(DomainRule(domain: domain, includeSubdomains: true))
        newDomain = ""
        save()
    }

    private func pickApplication() {
        let panel = NSOpenPanel()
        panel.title = "Select an Application to Block"
        panel.allowedContentTypes = [.application]
        panel.directoryURL = URL(fileURLWithPath: "/Applications")
        panel.allowsMultipleSelection = false
        panel.canChooseDirectories = false
        panel.canChooseFiles = true

        if panel.runModal() == .OK, let url = panel.url {
            let bundle = Bundle(url: url)
            let bundleID = bundle?.bundleIdentifier ?? url.deletingPathExtension().lastPathComponent
            let displayName = bundle?.infoDictionary?["CFBundleName"] as? String
                ?? bundle?.infoDictionary?["CFBundleDisplayName"] as? String
                ?? url.deletingPathExtension().lastPathComponent

            // Don't add duplicates
            guard !policy.applications.contains(where: { $0.nativeIdentifier == bundleID }) else { return }

            policy.applications.append(
                ApplicationRule(nativeIdentifier: bundleID, displayName: displayName)
            )
            save()
        }
    }

    private func save() {
        policy.updatedAt = Date()
        onSave(policy)
    }
}
