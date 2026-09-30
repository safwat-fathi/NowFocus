import NowFocusCore
import SwiftUI

// MARK: - Rules list

struct PoliciesView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        ScreenScaffold(title: "Rules") {
            Kicker(text: "Profiles")
            NowFocusRule(thick: true)
            ForEach(model.policies) { policy in
                VStack(spacing: 0) {
                    HStack {
                        Button { model.screen = .editPolicy(policy.id) } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(policy.name).font(NowFocusFonts.body(15).weight(.semibold))
                                Text("\(policy.domains.count) sites · \(policy.applications.count) apps")
                                    .font(NowFocusFonts.body(12)).foregroundColor(NowFocusColors.neutral700)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading).contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        if model.canWeaken(policyId: policy.id) {
                            NowFocusGhostButton(title: "Remove") { model.deletePolicy(policy.id) }
                        }
                    }
                    .padding(.vertical, NowFocusSpace.s2)
                    NowFocusRule()
                }
            }
            NowFocusSecondaryButton(title: "+ New profile") { model.screen = .editPolicy(model.addPolicy()) }
            Kicker(text: "Protections").padding(.top, NowFocusSpace.s4)
            NowFocusRule(thick: true)
            RuleRow(title: "Commitment Shield", detail: model.shieldLive ? "On" : "Off") { model.backTo = .policies; model.screen = .commitment }
            RuleRow(title: "Bedtime Wind-Down", detail: model.bedtime.enabled ? "On" : "Off") { model.backTo = .policies; model.screen = .bedtime }
            RuleRow(title: "People", detail: "\(model.people.count)") { model.screen = .people }
            RuleRow(title: "Goals", detail: "\(model.goals.count)") { model.screen = .goals }
        }
    }
}

// MARK: - Profile editor

struct PolicyEditorView: View {
    @Environment(AppModel.self) private var model
    let policyId: String
    @State private var name = ""
    @State private var newDomain = ""
    @State private var invalid = false

    var body: some View {
        if let policy = model.policy(policyId) {
            let canRemove = model.canWeaken(policyId: policyId)
            ScreenScaffold(title: "Edit profile", onBack: { model.screen = .policies }) {
                TextField("Profile name", text: $name).nfField()
                    .onChange(of: name) { _, new in
                        var p = policy; p.name = new.isEmpty ? policy.name : new; model.savePolicy(p)
                    }
                Kicker(text: "Blocked sites")
                NowFocusRule(thick: true)
                ForEach(policy.domains) { rule in
                    VStack(spacing: 0) {
                        HStack {
                            Text(rule.domain).font(NowFocusFonts.body(15))
                            NowFocusTagPill(text: "+ subdomains", accent: false)
                            Spacer()
                            if canRemove {
                                NowFocusGhostButton(title: "Remove") {
                                    var p = policy; p.domains.removeAll { $0.id == rule.id }; model.savePolicy(p)
                                }
                            }
                        }
                        .padding(.vertical, NowFocusSpace.s2)
                        NowFocusRule()
                    }
                }
                if !canRemove { BodyText(text: "A session is using this profile, so sites can be added but not removed until it ends.", small: true) }
                HStack {
                    TextField("example.com", text: $newDomain).keyboardType(.URL).nfField()
                    NowFocusSecondaryButton(title: "Add") { add(to: policy) }
                }
                if invalid { BodyText(text: "That doesn't look like a website address.", small: true) }
                Kicker(text: "Blocked apps").padding(.top, NowFocusSpace.s3)
                BodyText(text: "Picking apps needs Screen Time access from Apple. Until that's enabled on this iPhone, profiles block sites only.", small: true)
            }
            .onAppear { name = policy.name }
        }
    }

    private func add(to policy: BlockPolicy) {
        guard let domain = DomainValidation.normalize(newDomain) else { invalid = true; return }
        invalid = false
        guard !policy.domains.contains(where: { $0.domain == domain }) else { newDomain = ""; return }
        var p = policy
        p.domains.append(DomainRule(domain: domain))
        model.savePolicy(p)
        newDomain = ""
    }
}

// MARK: - Commitment Shield

struct CommitmentView: View {
    @Environment(AppModel.self) private var model
    @State private var domains: [String] = []
    @State private var newDomain = ""
    @State private var confirming = false

    var body: some View {
        let uptime = CommitmentShield.uptimeNow()
        ScreenScaffold(title: "Commitment Shield", onBack: { model.screen = model.backTo }) {
            if let shield = model.shield, model.shieldLive {
                if shield.canCancel(now: model.now, uptime: uptime) { grace(shield, uptime: uptime) } else { detail(shield, uptime: uptime) }
            } else {
                setup
            }
        }
        .onAppear { if domains.isEmpty { domains = model.policies.first?.domains.map(\.domain) ?? [] } }
    }

    @ViewBuilder private var setup: some View {
        BodyText(text: "Lock sites for 14 days. There is no way out once it starts, except a 60-second window to change your mind.")
        ForEach(domains, id: \.self) { d in
            RuleRow(title: d, detail: "Remove") { domains.removeAll { $0 == d } }
        }
        HStack {
            TextField("example.com", text: $newDomain).keyboardType(.URL).nfField()
            NowFocusSecondaryButton(title: "Add") {
                if let d = DomainValidation.normalize(newDomain), !domains.contains(d) { domains.append(d); newDomain = "" }
            }
        }
        if confirming {
            Kicker(text: "Are you sure?")
            NowFocusPrimaryButton(title: "Yes, commit") { model.createShield(domains: domains); confirming = false }
            NowFocusGhostButton(title: "Not yet") { confirming = false }
        } else {
            NowFocusPrimaryButton(title: "Lock for 14 days", enabled: !domains.isEmpty) { confirming = true }
        }
    }

    @ViewBuilder private func grace(_ shield: CommitmentShield, uptime: TimeInterval) -> some View {
        let left = max(0, CommitmentShield.grace - model.now.timeIntervalSince(shield.startAt))
        Kicker(text: "Locking it in…")
        Text("\(Int(left.rounded(.up)))s").font(NowFocusFonts.heading(56)).monospacedDigit()
        NowFocusSecondaryButton(title: "Cancel") { model.cancelShield() }
    }

    @ViewBuilder private func detail(_ shield: CommitmentShield, uptime: TimeInterval) -> some View {
        let days = Int((shield.remaining(now: model.now, uptime: uptime) / 86_400).rounded(.up))
        Text("\(days)").font(NowFocusFonts.heading(72))
        BodyText(text: "day\(days == 1 ? "" : "s") to go")
        if !model.enforcer.layers.allSatisfy(\.ok) {
            NowFocusTagPill(text: "Blocking not active on this iPhone yet", accent: true)
        }
        Kicker(text: "Locked sites")
        ForEach(shield.domains, id: \.self) { RuleRow(title: $0) }
        BodyText(text: "Screen Time access can be switched off in Settings, so this is a commitment, not a hard lock.", small: true)
    }
}

// MARK: - Bedtime

struct BedtimeView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        ScreenScaffold(title: "Bedtime Wind-Down", onBack: { model.screen = model.backTo }) {
            HStack {
                Text("On every night").font(NowFocusFonts.body(15).weight(.semibold))
                Spacer()
                NFToggle(label: "On every night", isOn: $model.bedtime.enabled)
            }
            BodyText(text: "From wind-down until wake, your chosen profile is enforced as a locked session.", small: true)
            timeRow("Wind-down", minute: $model.bedtime.windDownMinute)
            timeRow("Sleep", minute: $model.bedtime.sleepMinute)
            timeRow("Wake", minute: $model.bedtime.wakeMinute)
            Kicker(text: "Block profile").padding(.top, NowFocusSpace.s2)
            if model.policies.isEmpty {
                BodyText(text: "No profiles yet. Create one in Rules first, or nothing will be blocked at bedtime.", small: true)
            }
            ForEach(model.policies) { policy in
                RuleRow(title: policy.name + (policy.id == model.bedtime.policyId ? "  ✓" : "")) { model.bedtime.policyId = policy.id }
            }
            BodyText(text: "The schedule is saved now. Nightly blocking starts once Screen Time access is enabled on this iPhone.", small: true)
        }
    }

    private func timeRow(_ label: String, minute: Binding<Int>) -> some View {
        let date = Binding<Date>(
            get: { Calendar.current.date(bySettingHour: minute.wrappedValue / 60, minute: minute.wrappedValue % 60, second: 0, of: Date()) ?? Date() },
            set: { let c = Calendar.current.dateComponents([.hour, .minute], from: $0); minute.wrappedValue = (c.hour ?? 0) * 60 + (c.minute ?? 0) }
        )
        return VStack(spacing: 0) {
            HStack {
                Text(label).font(NowFocusFonts.body(15))
                Spacer()
                DatePicker("", selection: date, displayedComponents: .hourAndMinute).labelsHidden()
            }
            .padding(.vertical, NowFocusSpace.s2)
            NowFocusRule()
        }
    }
}
