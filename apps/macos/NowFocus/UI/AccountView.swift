import SwiftUI
import NowFocusCore

/// Optional account: sign in to keep profiles and bedtime settings in sync. Without one, nothing here ever contacts
/// a server (the privacy page promises it).
struct AccountView: View {
    @ObservedObject private var sync = SyncController.shared

    @State private var email = ""
    @State private var password = ""
    @State private var busy = false
    @State private var error: String?
    @State private var devices: [DeviceInfo] = []
    @State private var confirmingDelete = false
    @State private var deletePassword = ""

    var body: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s3) {
            Text("ACCOUNT")
                .font(NowFocusFonts.body(11).weight(.semibold))
                .nfTracking(1.0)
                .foregroundColor(NowFocusColors.neutral700)
            NowFocusRule(thick: true)

            if sync.status.signedIn { signedIn } else if sync.status.loaded { signedOut }
        }
        // The device list is a network call, so it is only made while signed in.
        .task(id: sync.status.signedIn) { await loadDevices() }
        .onChange(of: sync.status.lastSyncedAt) { Task { await loadDevices() } }
    }

    // MARK: signed out

    private var signedOut: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s3) {
            Text("Optional. Sign in to keep your profiles and bedtime settings in sync across your devices. People, goals, voice notes and stats stay on this Mac, and NowFocus keeps blocking from this Mac\u{2019}s own copy even when you\u{2019}re offline.")
                .font(NowFocusFonts.body(13))
                .foregroundColor(NowFocusColors.neutral800)
                .fixedSize(horizontal: false, vertical: true)

            if let problem = sync.status.problem { errorText(problem) }

            TextField("Email", text: $email)
                .textContentType(.emailAddress)
                .modifier(FieldStyle())
            SecureField("Password (8+ characters)", text: $password)
                .modifier(FieldStyle())
                .onSubmit { submit(create: false) }

            if let error { errorText(error) }

            NowFocusPrimaryButton(title: busy ? "Please wait\u{2026}" : "Sign in", enabled: !busy && canSubmit) { submit(create: false) }
            NowFocusSecondaryButton(title: "Create account") { if !busy && canSubmit { submit(create: true) } }
        }
        .frame(maxWidth: 420, alignment: .leading)
    }

    private var canSubmit: Bool { !email.trimmingCharacters(in: .whitespaces).isEmpty && !password.isEmpty }

    private func submit(create: Bool) {
        guard !busy, canSubmit else { return }
        busy = true; error = nil
        Task {
            error = await sync.signIn(email: email, password: password, createAccount: create)
            busy = false
            if error == nil { password = "" }
        }
    }

    // MARK: signed in

    private var signedIn: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s3) {
            Text("Signed in as \(sync.status.email ?? "")")
                .font(NowFocusFonts.body(15).weight(.semibold))
                .foregroundColor(NowFocusColors.ink)
            Text(statusLine)
                .font(NowFocusFonts.body(12))
                .foregroundColor(sync.status.problem == nil ? NowFocusColors.neutral700 : NowFocusColors.accent700)
            if sync.status.rejected > 0 {
                errorText(String(localized: "\(sync.status.rejected) changes couldn\u{2019}t be synced. They stay on this Mac; edit them to try again."))
            }
            Text("Profiles and bedtime settings sync. People, goals, voice notes and stats stay on this Mac.")
                .font(NowFocusFonts.body(12))
                .foregroundColor(NowFocusColors.neutral700)

            HStack(spacing: NowFocusSpace.s2) {
                NowFocusSecondaryButton(title: "Sync now") { sync.syncNow() }
                NowFocusSecondaryButton(title: "Sign out") { Task { await sync.signOut() } }
            }

            if let error { errorText(error) }

            Text("YOUR DEVICES")
                .font(NowFocusFonts.body(11).weight(.semibold))
                .nfTracking(1.0)
                .foregroundColor(NowFocusColors.neutral700)
                .padding(.top, NowFocusSpace.s3)
            NowFocusRule()
            ForEach(devices.filter { !$0.revoked }) { device in deviceRow(device) }

            if confirmingDelete { deleteForm } else {
                NowFocusGhostButton(title: "Delete account\u{2026}") { confirmingDelete = true; error = nil }
            }
        }
    }

    private var statusLine: String {
        if let problem = sync.status.problem { return problem }
        if sync.status.syncing { return String(localized: "Syncing\u{2026}") }
        guard let last = sync.status.lastSyncedAt else { return String(localized: "Not synced yet.") }
        return String(localized: "Synced \(last.formatted(.relative(presentation: .named).locale(AppLanguage.locale))).")
    }

    private func deviceRow(_ device: DeviceInfo) -> some View {
        HStack(spacing: NowFocusSpace.s2) {
            VStack(alignment: .leading, spacing: 2) {
                Text(device.name.isEmpty ? device.platform : device.name)
                    .font(NowFocusFonts.body(14).weight(.semibold))
                    .foregroundColor(NowFocusColors.ink)
                Text(verbatim: "\(device.platform)\(device.lastSeenAt.map { " \u{00B7} " + String(localized: "seen \($0.formatted(.relative(presentation: .named).locale(AppLanguage.locale)))") } ?? "")")
                    .font(NowFocusFonts.body(12))
                    .foregroundColor(NowFocusColors.neutral700)
            }
            Spacer()
            if device.current {
                NowFocusTagPill(text: "This Mac", accent: false)
            } else {
                NowFocusGhostButton(title: "Revoke") {
                    Task {
                        error = await sync.revokeDevice(id: device.id)
                        await loadDevices()
                    }
                }
            }
        }
        .padding(.vertical, NowFocusSpace.s2)
        .overlay(alignment: .bottom) { NowFocusRule() }
    }

    private var deleteForm: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s2) {
            Text("This permanently deletes your account and everything synced to it, on every device. Nothing on this Mac is deleted, and blocking is unaffected.")
                .font(NowFocusFonts.body(12))
                .foregroundColor(NowFocusColors.neutral800)
                .fixedSize(horizontal: false, vertical: true)
            SecureField("Confirm with your password", text: $deletePassword)
                .modifier(FieldStyle())
                .frame(maxWidth: 320)
            HStack(spacing: NowFocusSpace.s2) {
                NowFocusSecondaryButton(title: busy ? "Deleting\u{2026}" : "Delete account") {
                    guard !busy, !deletePassword.isEmpty else { return }
                    busy = true; error = nil
                    Task {
                        error = await sync.deleteAccount(password: deletePassword)
                        busy = false
                        if error == nil { deletePassword = ""; confirmingDelete = false }
                    }
                }
                NowFocusGhostButton(title: "Cancel") { if !busy { confirmingDelete = false; deletePassword = ""; error = nil } }
            }
        }
        .padding(.top, NowFocusSpace.s2)
    }

    // MARK: helpers

    private func loadDevices() async {
        guard sync.status.signedIn else { devices = []; return }
        devices = (try? await sync.devices()) ?? devices
    }

    private func errorText(_ text: String) -> some View {
        Text(text)
            .font(NowFocusFonts.body(12))
            .foregroundColor(NowFocusColors.accent700)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// The design system's input: square, 1px rule border (Windows `.input`, iOS `nfField`).
private struct FieldStyle: ViewModifier {
    func body(content: Content) -> some View {
        content
            .textFieldStyle(.plain)
            .font(NowFocusFonts.body(14))
            .foregroundColor(NowFocusColors.ink)
            .padding(NowFocusSpace.s2)
            .overlay(Rectangle().stroke(NowFocusColors.divider, lineWidth: 1))
            .disableAutocorrection(true)
    }
}
