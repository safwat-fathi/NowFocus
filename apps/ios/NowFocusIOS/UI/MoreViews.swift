import Contacts
import ContactsUI
import NowFocusCore
import SwiftUI

// MARK: - Stats

struct StatsView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let cal = HistoryStats.mondayFirstCalendar()
        let week = cal.dateInterval(of: .weekOfYear, for: model.now) ?? DateInterval(start: model.now, duration: 7 * 86_400)
        let sessions = model.sessions(from: week.start, to: week.end)
        let recent = model.sessions(from: model.now.addingTimeInterval(-60 * 86_400), to: week.end)
        let events = model.blockEvents(from: week.start, to: week.end)
        let urges = model.blockEvents(from: model.now.addingTimeInterval(-28 * 86_400), to: model.now)
        let buckets = HistoryStats.weekBucketsMinutes(sessions, anyDayInWeek: model.now, calendar: cal)
        let peak = HistoryStats.peakUrge(urges, calendar: cal)

        ScreenScaffold(title: "Stats") {
            Kicker(text: "This week")
            Text(Fmt.focused(HistoryStats.totalFocusedSeconds(sessions))).font(NowFocusFonts.heading(48))
            HStack(alignment: .bottom, spacing: NowFocusSpace.s2) {
                ForEach(0..<7, id: \.self) { i in
                    VStack(spacing: 4) {
                        Rectangle().fill(NowFocusColors.accent)
                            .frame(height: max(2, 80 * CGFloat(buckets[i] / max(buckets.max() ?? 1, 1))))
                        Text(["M", "T", "W", "T", "F", "S", "S"][i]).font(NowFocusFonts.body(11)).foregroundColor(NowFocusColors.neutral700)
                    }
                    .frame(maxWidth: .infinity)
                }
            }
            .frame(height: 100, alignment: .bottom)
            HStack(spacing: NowFocusSpace.s2) {
                StatCell(label: "Sessions", value: "\(sessions.count)")
                StatCell(label: "Completed", value: "\(Int((HistoryStats.completionRate(sessions) * 100).rounded()))%")
            }
            HStack(spacing: NowFocusSpace.s2) {
                StatCell(label: "Turned away", value: "\(HistoryStats.turnedAwayCount(events))")
                StatCell(label: "Day streak", value: "\(HistoryStats.currentStreakDays(recent, today: model.now, calendar: cal))")
            }
            let top = HistoryStats.topBlockedApps(events)
            if !top.isEmpty {
                Kicker(text: "Most turned away").padding(.top, NowFocusSpace.s2)
                ForEach(top, id: \.name) { RuleRow(title: $0.name, detail: "\($0.count)") }
            }
            if let peak {
                Kicker(text: "Your urges").padding(.top, NowFocusSpace.s2)
                BodyText(text: "You tried to open a blocked app \(peak.count) times around \(Fmt.clock(peak.hour * 60)), usually \(peak.topApp).")
            }
            BodyText(text: "Counted on your phone. We never see what you browse.", small: true).padding(.top, NowFocusSpace.s3)
        }
    }
}

// MARK: - Devices

struct DevicesView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let layers = model.enforcer.layers
        let healthy = layers.allSatisfy(\.ok)
        ScreenScaffold(title: "Devices") {
            BodyText(text: "This phone only, for now. Linking other devices isn't available yet.", small: true)
            NowFocusRule(thick: true)
            HStack {
                Text("This iPhone").font(NowFocusFonts.heading(17))
                Spacer()
                NowFocusTagPill(text: healthy ? "Active" : "Degraded", accent: !healthy)
            }
            HStack(spacing: 0) {
                ForEach(layers) { layer in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(layer.ok ? "On" : "Off").font(NowFocusFonts.body(12).weight(.semibold))
                            .foregroundColor(layer.ok ? NowFocusColors.ink : NowFocusColors.accent700)
                        Text(layer.name).font(NowFocusFonts.body(12)).foregroundColor(NowFocusColors.neutral700)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading).padding(NowFocusSpace.s2)
                    .overlay(Rectangle().stroke(NowFocusColors.divider, lineWidth: 1))
                }
            }
            // One button per missing layer, straight to that permission.
            ForEach(layers.filter { !$0.ok }) { layer in
                if let title = layer.fixTitle, let fix = layer.fix {
                    NowFocusGhostButton(title: title, action: fix)
                } else {
                    BodyText(text: "\(layer.name) needs Screen Time access from Apple, which isn't enabled in this build yet.", small: true)
                }
            }
            NowFocusRule()
        }
    }
}

// MARK: - Goals

struct GoalsEditor: View {
    @Environment(AppModel.self) private var model
    @State private var text = ""
    @State private var priority: GoalPriority = .high

    var body: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s3) {
            ForEach(model.goals) { goal in
                RuleRow(title: goal.text, detail: "\(goal.priority.label) · Remove") { model.removeGoal(goal.id) }
            }
            TextField("What are you focusing for?", text: $text).nfField().textInputAutocapitalization(.sentences)
            HStack {
                ForEach(GoalPriority.allCases, id: \.self) { p in Chip(label: p.label, selected: p == priority) { priority = p } }
                Spacer()
                NowFocusSecondaryButton(title: "Add") { model.addGoal(text, priority: priority); text = "" }
            }
        }
    }
}

struct GoalsView: View {
    @Environment(AppModel.self) private var model
    var body: some View {
        ScreenScaffold(title: "Goals", onBack: { model.screen = .policies }) {
            BodyText(text: "Short reminders of what you're focusing for. One shows up when you try to end a Strict session.", small: true)
            GoalsEditor()
        }
    }
}

// MARK: - People

struct PeopleEditor: View {
    @Environment(AppModel.self) private var model
    @State private var picking = false
    @State private var message: String?

    var body: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s3) {
            ForEach(model.people) { person in
                VStack(alignment: .leading, spacing: NowFocusSpace.s2) {
                    HStack {
                        Text(person.name).font(NowFocusFonts.body(15).weight(.semibold))
                        Text(person.phoneNumber ?? "").font(NowFocusFonts.body(12)).foregroundColor(NowFocusColors.neutral700)
                        Spacer()
                        NowFocusGhostButton(title: "Remove") { model.removePerson(person.id) }
                    }
                    Kicker(text: "Last talked")
                    let current = LastTalked.choice(for: person.lastTalkedAt, now: model.now)
                    FlowChips(items: LastTalked.choices.map(\.label), label: { $0 },
                              isSelected: { label in LastTalked.choices.first { $0.label == label }?.daysAgo == current },
                              onTap: { label in model.setLastTalked(person.id, daysAgo: LastTalked.choices.first { $0.label == label }?.daysAgo ?? nil) })
                    NowFocusRule()
                }
            }
            if model.people.count < PeopleRotation.max {
                NowFocusSecondaryButton(title: "+ Add from contacts") { picking = true }
            }
            if let message { BodyText(text: message, small: true) }
        }
        .sheet(isPresented: $picking) {
            ContactPicker { name, phone in
                message = model.addPerson(name: name, phone: phone) ? nil : "That number is already listed, or the list is full."
            }
            .ignoresSafeArea()
        }
    }
}

struct PeopleView: View {
    @Environment(AppModel.self) private var model
    var body: some View {
        ScreenScaffold(title: "People", onBack: { model.screen = .policies }) {
            BodyText(text: "Up to \(PeopleRotation.max) people you'd rather hear from than scroll. Numbers stay on this phone.", small: true)
            PeopleEditor()
        }
    }
}

/// System contact picker: needs no Contacts permission, the app only receives what's tapped.
struct ContactPicker: UIViewControllerRepresentable {
    let onPick: (String, String) -> Void

    func makeUIViewController(context: Context) -> CNContactPickerViewController {
        let picker = CNContactPickerViewController()
        picker.delegate = context.coordinator
        picker.displayedPropertyKeys = [CNContactPhoneNumbersKey]
        return picker
    }
    func updateUIViewController(_ vc: CNContactPickerViewController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(onPick: onPick) }

    final class Coordinator: NSObject, CNContactPickerDelegate {
        let onPick: (String, String) -> Void
        init(onPick: @escaping (String, String) -> Void) { self.onPick = onPick }
        func contactPicker(_ picker: CNContactPickerViewController, didSelect property: CNContactProperty) {
            guard let phone = (property.value as? CNPhoneNumber)?.stringValue else { return }
            onPick(CNContactFormatter.string(from: property.contact, style: .fullName) ?? phone, phone)
        }
    }
}

// MARK: - Onboarding

struct OnboardingView: View {
    @Environment(AppModel.self) private var model
    @State private var step = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Rectangle().fill(NowFocusColors.neutral200)
                    Rectangle().fill(NowFocusColors.accent).frame(width: geo.size.width * CGFloat(step + 1) / 4)
                }
            }
            .frame(height: 4)
            ScreenScaffold(title: ["Welcome", "Your goals", "People", "Permissions"][step]) {
                switch step {
                case 0:
                    BodyText(text: "NowFocus blocks the things that pull you off course, for as long as you choose.")
                    RuleRow(title: "Block sites and apps for a set time")
                    RuleRow(title: "Normal, Strict or Locked, your call")
                    RuleRow(title: "Everything stays on this phone")
                case 1:
                    BodyText(text: "What are you focusing for? You'll see one when you try to quit.", small: true)
                    GoalsEditor()
                case 2:
                    BodyText(text: "Who would you rather talk to than scroll? Optional.", small: true)
                    PeopleEditor()
                default:
                    ForEach(model.enforcer.layers) { layer in
                        RuleRow(title: layer.name, detail: layer.ok ? "On" : "Needs Apple approval")
                    }
                    BodyText(text: "Blocking uses Apple's Screen Time. It isn't enabled in this build yet; everything else works.", small: true)
                }
                NowFocusPrimaryButton(title: step == 3 ? "Get started" : "Continue") {
                    if step == 3 { model.completeOnboarding() } else { step += 1 }
                }
                .padding(.top, NowFocusSpace.s3)
                if step == 1 || step == 2 { NowFocusGhostButton(title: "Skip for now") { step += 1 } }
            }
        }
    }
}
