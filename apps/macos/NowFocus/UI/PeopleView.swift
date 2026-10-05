import SwiftUI
import NowFocusCore

/// Add, remove and date the people the block screen points you to. Up to five, each with a number.
/// Persists as you go; used by the "People who matter" screen and the onboarding step.
struct PeopleEditorView: View {
    @State private var people: [UserConnection] = []
    @State private var newName = ""
    @State private var newPhone = ""
    @State private var addError: String?
    /// Numbers being typed for older rows that were saved without one.
    @State private var missingPhone: [String: String] = [:]

    var body: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s3) {
            ForEach(people) { person in
                personRow(person)
            }
            if people.count < PeopleRotation.max {
                addRow
            }
        }
        .onAppear(perform: load)
    }

    private func load() {
        people = (try? DatabaseManager.shared.fetchAllConnections()) ?? []
    }

    // MARK: - Rows

    @ViewBuilder
    private func personRow(_ person: UserConnection) -> some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s2) {
            HStack(spacing: NowFocusSpace.s2) {
                Image(systemName: "person.circle")
                    .foregroundColor(NowFocusColors.neutral500)
                VStack(alignment: .leading, spacing: 2) {
                    Text(person.name)
                        .font(NowFocusFonts.body(14).weight(.semibold))
                        .foregroundColor(NowFocusColors.ink)
                    if let phone = person.phoneNumber, !phone.isEmpty {
                        Text(phone)
                            .font(NowFocusFonts.body(12))
                            .foregroundColor(NowFocusColors.neutral700)
                    }
                }
                Spacer()
                NowFocusGhostButton(title: "Remove") {
                    try? DatabaseManager.shared.deleteConnection(id: person.id)
                    load()
                }
            }

            if person.phoneNumber?.trimmingCharacters(in: .whitespaces).isEmpty ?? true {
                addNumberRow(person)
            } else {
                Text("LAST TALKED")
                    .font(NowFocusFonts.body(11).weight(.semibold))
                    .nfTracking(1.1)
                    .foregroundColor(NowFocusColors.neutral700)
                lastTalkedChips(person)
            }
        }
        .padding(.bottom, NowFocusSpace.s2)
        .overlay(alignment: .bottom) { NowFocusRule() }
    }

    /// Rows from before numbers were required stay listed but can't be picked until they get one.
    private func addNumberRow(_ person: UserConnection) -> some View {
        HStack(spacing: NowFocusSpace.s2) {
            TextField("Add a number to reach them", text: Binding(
                get: { missingPhone[person.id] ?? "" },
                set: { missingPhone[person.id] = $0 }
            ))
            .textFieldStyle(.plain)
            .font(NowFocusFonts.body(13))
            .padding(.vertical, NowFocusSpace.s1)
            .overlay(NowFocusRule(), alignment: .bottom)
            NowFocusSecondaryButton(title: "Save") {
                let phone = (missingPhone[person.id] ?? "").trimmingCharacters(in: .whitespaces)
                guard PhoneLink.dialString(phone) != nil,
                      !people.contains(where: { $0.id != person.id && $0.phoneNumber == phone }) else { return }
                var updated = person
                updated.phoneNumber = phone
                updated.updatedAt = Date()
                try? DatabaseManager.shared.saveConnection(updated)
                missingPhone[person.id] = nil
                load()
            }
        }
    }

    private func lastTalkedChips(_ person: UserConnection) -> some View {
        let now = Date()
        let selected = LastTalked.choice(for: person.lastTalkedAt, now: now)
        return HStack(spacing: NowFocusSpace.s1) {
            ForEach(Array(LastTalked.choices.enumerated()), id: \.offset) { _, choice in
                let isSelected = choice.daysAgo == selected
                Button {
                    try? DatabaseManager.shared.setLastTalked(connectionId: person.id, to: LastTalked.date(daysAgo: choice.daysAgo, now: now))
                    load()
                } label: {
                    Text(choice.label)
                        .font(NowFocusFonts.body(12).weight(.semibold))
                        .foregroundColor(isSelected ? NowFocusColors.ground : NowFocusColors.ink)
                        .padding(.horizontal, NowFocusSpace.s2)
                        .padding(.vertical, NowFocusSpace.s1)
                        .background(isSelected ? NowFocusColors.ink : Color.clear)
                        .overlay(Rectangle().stroke(NowFocusColors.divider, lineWidth: 1))
                }
                .buttonStyle(.plain)
            }
        }
    }

    private var addRow: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s2) {
            HStack(spacing: NowFocusSpace.s2) {
                TextField("Name", text: $newName)
                    .textFieldStyle(.plain)
                    .font(NowFocusFonts.body(14))
                    .foregroundColor(NowFocusColors.ink)
                    .padding(.vertical, NowFocusSpace.s2)
                    .overlay(NowFocusRule(), alignment: .bottom)
                    .frame(maxWidth: 160)

                TextField("Phone number", text: $newPhone)
                    .textFieldStyle(.plain)
                    .font(NowFocusFonts.body(14))
                    .foregroundColor(NowFocusColors.ink)
                    .padding(.vertical, NowFocusSpace.s2)
                    .overlay(NowFocusRule(), alignment: .bottom)
                    .onSubmit(add)

                NowFocusSecondaryButton(title: "+ Add someone", action: add)
            }
            if let addError {
                Text(addError)
                    .font(NowFocusFonts.body(12))
                    .foregroundColor(NowFocusColors.accent700)
            }
        }
    }

    private func add() {
        let name = newName.trimmingCharacters(in: .whitespaces)
        let phone = newPhone.trimmingCharacters(in: .whitespaces)
        guard !name.isEmpty else { addError = String(localized: "Enter a name."); return }
        guard PhoneLink.dialString(phone) != nil else { addError = String(localized: "Enter a phone number, so there's someone to call."); return }
        guard PeopleRotation.canAdd(phone: phone, to: people) else { addError = String(localized: "That number is already on the list."); return }
        try? DatabaseManager.shared.saveConnection(UserConnection(name: name, phoneNumber: phone))
        newName = ""
        newPhone = ""
        addError = nil
        load()
    }
}

/// "People who matter": the sidebar screen around the editor.
struct PeopleView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: NowFocusSpace.s4) {
                Text("People who matter")
                    .font(NowFocusFonts.heading(28))
                    .foregroundColor(NowFocusColors.ink)
                Text("When a blocked app opens, we'll point you to whoever you've talked to least recently, with a way to call or text. Up to five. Nothing leaves this Mac.")
                    .font(NowFocusFonts.body(13))
                    .foregroundColor(NowFocusColors.neutral700)
                    .fixedSize(horizontal: false, vertical: true)
                NowFocusRule(thick: true)
                PeopleEditorView()
            }
            .padding(NowFocusSpace.s6)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}
