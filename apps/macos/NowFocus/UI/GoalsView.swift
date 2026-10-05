import SwiftUI
import NowFocusCore

/// Add and remove goals, each High / Med / Low. Persists as you go; used by the "Your goals" screen and
/// the onboarding step.
struct GoalsEditorView: View {
    @State private var goals: [UserGoal] = []
    @State private var newGoalText = ""
    @State private var newGoalPriority: GoalPriority = .high

    var body: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s3) {
            VStack(spacing: 0) {
                ForEach(goals) { goal in
                    HStack(spacing: NowFocusSpace.s2) {
                        NowFocusTagPill(text: LocalizedStringKey(goal.priority.label), accent: goal.priority == .high)
                            .frame(width: 42)
                        Text(goal.text)
                            .font(NowFocusFonts.body(14))
                            .foregroundColor(NowFocusColors.ink)
                        Spacer()
                        NowFocusGhostButton(title: "Remove") {
                            try? DatabaseManager.shared.deleteGoal(id: goal.id)
                            load()
                        }
                    }
                    .padding(.vertical, NowFocusSpace.s2)
                    .overlay(alignment: .bottom) { NowFocusRule() }
                }
            }

            HStack(spacing: NowFocusSpace.s2) {
                TextField("Add a goal, e.g. Finish my thesis", text: $newGoalText)
                    .textFieldStyle(.plain)
                    .font(NowFocusFonts.body(14))
                    .foregroundColor(NowFocusColors.ink)
                    .padding(.vertical, NowFocusSpace.s2)
                    .overlay(NowFocusRule(), alignment: .bottom)
                    .onSubmit(add)

                NowFocusSegmentedControl(
                    options: GoalPriority.allCases.map { (label: LocalizedStringKey($0.label), value: $0) },
                    selection: $newGoalPriority
                )
                .frame(width: 150)

                NowFocusSecondaryButton(title: "Add", action: add)
            }
        }
        .onAppear(perform: load)
    }

    private func load() {
        goals = (try? DatabaseManager.shared.fetchAllGoals()) ?? []
    }

    private func add() {
        let text = newGoalText.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return }
        try? DatabaseManager.shared.saveGoal(UserGoal(text: text, priority: newGoalPriority))
        newGoalText = ""
        load()
    }
}

/// "Your goals": the sidebar screen around the editor.
struct GoalsView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: NowFocusSpace.s4) {
                Text("Your goals")
                    .font(NowFocusFonts.heading(28))
                    .foregroundColor(NowFocusColors.ink)
                Text("What you're focusing for. Shown while you wait to end a Strict session early, and on the block screen when no one is set to reach out to. Stays on this Mac.")
                    .font(NowFocusFonts.body(13))
                    .foregroundColor(NowFocusColors.neutral700)
                    .fixedSize(horizontal: false, vertical: true)
                NowFocusRule(thick: true)
                GoalsEditorView()
            }
            .padding(NowFocusSpace.s6)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}
