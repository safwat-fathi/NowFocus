import SwiftUI
import NowFocusCore

// MARK: - Container

/// Multi-step onboarding wizard shown on first launch.
/// Replaces the main window content until `onComplete` is called.
struct OnboardingView: View {
    let onComplete: () -> Void

    @State private var step: Int = 0

    // Step 1 – Bedtime (seeds Bedtime Wind-Down; still off until you turn it on)
    @State private var sleepTime: Date = minutesToDate(23 * 60)
    @State private var wakeTime: Date  = minutesToDate(7 * 60)

    private let totalSteps = 4  // 0..3: Welcome, Bedtime, Goals, People

    var body: some View {
        HStack(spacing: 0) {
            // Left brand rail
            brandRail

            NowFocusRule(vertical: true)

            // Right content pane
            VStack(alignment: .leading, spacing: 0) {
                stepIndicator
                    .padding(.bottom, NowFocusSpace.s6)

                stepContent
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                    .transition(.asymmetric(
                        insertion: .move(edge: .trailing).combined(with: .opacity),
                        removal:   .move(edge: .leading).combined(with: .opacity)
                    ))

                Spacer(minLength: 0)
                navButtons
            }
            .padding(NowFocusSpace.s8)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(NowFocusColors.ground)
        }
        .frame(minWidth: 880, minHeight: 540)
        .background(NowFocusColors.ground)
    }

    // MARK: - Brand Rail

    private var brandRail: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s3) {
            Text("NowFocus")
                .font(NowFocusFonts.heading(28))
                .foregroundColor(NowFocusColors.ink)

            Text("Your focus,\nyour rules.")
                .font(NowFocusFonts.heading(18))
                .foregroundColor(NowFocusColors.neutral700)
                .lineSpacing(4)

            Spacer(minLength: 0)

            Text("This takes\nabout 2 minutes.")
                .font(NowFocusFonts.body(12))
                .foregroundColor(NowFocusColors.neutral600)
        }
        .padding(NowFocusSpace.s8)
        .frame(width: 220)
        .background(NowFocusColors.neutral100)
    }

    // MARK: - Step indicator

    private var stepIndicator: some View {
        HStack(spacing: NowFocusSpace.s2) {
            ForEach(0..<totalSteps, id: \.self) { i in
                Circle()
                    .fill(i <= step ? NowFocusColors.accent : NowFocusColors.neutral300)
                    .frame(width: 7, height: 7)
                    .animation(.easeInOut(duration: 0.2), value: step)
            }
            Spacer()
        }
    }

    // MARK: - Step content router

    @ViewBuilder
    private var stepContent: some View {
        switch step {
        case 0: welcomeStep
        case 1: scheduleStep
        case 2: goalsStep
        case 3: peopleStep
        default: EmptyView()
        }
    }

    // MARK: - Navigation

    private var nextLabel: LocalizedStringKey {
        step == totalSteps - 1 ? "Get Started →" : "Continue →"
    }

    private var navButtons: some View {
        HStack(spacing: NowFocusSpace.s3) {
            if step > 0 {
                NowFocusSecondaryButton(title: "← Back") {
                    withAnimation { step -= 1 }
                }
            }
            Spacer()

            // Everything after Welcome is optional.
            if step >= 1 {
                NowFocusGhostButton(title: "Skip") {
                    advanceOrComplete()
                }
            }

            NowFocusPrimaryButton(title: nextLabel) {
                saveCurrentStep()
                advanceOrComplete()
            }
        }
        .padding(.top, NowFocusSpace.s4)
    }

    private func advanceOrComplete() {
        withAnimation {
            if step < totalSteps - 1 {
                step += 1
            } else {
                finish()
            }
        }
    }

    private func finish() {
        onComplete()
    }

    // MARK: - Save per step

    // Goals and people save as they're added (see the editors), so only Bedtime needs saving here.
    private func saveCurrentStep() {
        if step == 1 { saveBedtime() }
    }

    private func saveBedtime() {
        let sleepMin = dateToMinutes(sleepTime)
        let wakeMin  = dateToMinutes(wakeTime)
        var bedtime  = BedtimeSettingsStore.shared.settings
        bedtime.sleepMinute     = sleepMin
        bedtime.wakeMinute      = wakeMin
        bedtime.windDownMinute  = max(0, sleepMin - 60)
        bedtime.enabled         = false // user enables manually
        BedtimeSettingsStore.shared.settings = bedtime
    }

    // MARK: - Step 0: Welcome

    private var welcomeStep: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s4) {
            Text("What matters\nto you?")
                .font(NowFocusFonts.heading(36))
                .foregroundColor(NowFocusColors.ink)
                .lineSpacing(4)

            NowFocusRule(thick: true)

            Text("NowFocus will remind you of your goals and the people you care about when you're tempted to break focus or quit a session early.")
                .font(NowFocusFonts.body(15))
                .foregroundColor(NowFocusColors.neutral800)
                .fixedSize(horizontal: false, vertical: true)
                .lineSpacing(3)

            Text("A few quick questions, all optional, and you're done. Everything stays on this Mac.")
                .font(NowFocusFonts.body(14))
                .foregroundColor(NowFocusColors.neutral700)
                .fixedSize(horizontal: false, vertical: true)
                .lineSpacing(3)
        }
    }

    // MARK: - Step 1: Bedtime

    private var scheduleStep: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s6) {
            stepHeader(
                title: "When do you\nsleep?",
                body: "NowFocus can wind this Mac down at night. You can change these anytime."
            )

            scheduleSection(label: "BEDTIME") {
                VStack(spacing: 0) {
                    timeRow("Sleep at", binding: $sleepTime)
                    timeRow("Wake at",  binding: $wakeTime)
                }
            }

            Text("Bedtime Wind-Down is off by default — enable it in Preferences when you're ready.")
                .font(NowFocusFonts.body(12))
                .foregroundColor(NowFocusColors.neutral600)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func scheduleSection<Content: View>(label: LocalizedStringKey, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s2) {
            Text(label)
                .font(NowFocusFonts.body(11).weight(.semibold))
                .nfTracking(1.0)
                .foregroundColor(NowFocusColors.neutral700)
            NowFocusRule()
            content()
        }
    }

    private func timeRow(_ label: LocalizedStringKey, binding: Binding<Date>) -> some View {
        HStack {
            Text(label)
                .font(NowFocusFonts.body(14))
                .foregroundColor(NowFocusColors.ink)
            Spacer()
            DatePicker("", selection: binding, displayedComponents: .hourAndMinute)
                .labelsHidden()
        }
        .padding(.vertical, NowFocusSpace.s2)
        .overlay(alignment: .bottom) { NowFocusRule() }
    }

    // MARK: - Step 2: Goals

    private var goalsStep: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s4) {
            stepHeader(
                title: "What are you\nfocusing for?",
                body: "We'll remind you while you wait to end a Strict session early, and when a blocked app opens."
            )
            GoalsEditorView()
        }
    }

    // MARK: - Step 3: People

    private var peopleStep: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s4) {
            stepHeader(
                title: "Who matters\nto you?",
                body: "When a blocked app opens, we'll point you to someone you haven't talked to in a while, with a way to call or text. Nothing leaves this Mac."
            )
            PeopleEditorView()
        }
    }

    // MARK: - Helpers

    private func stepHeader(title: LocalizedStringKey, body: LocalizedStringKey) -> some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s3) {
            Text(title)
                .font(NowFocusFonts.heading(30))
                .foregroundColor(NowFocusColors.ink)
                .lineSpacing(3)
            NowFocusRule(thick: true)
            Text(body)
                .font(NowFocusFonts.body(14))
                .foregroundColor(NowFocusColors.neutral800)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

// MARK: - Utilities

private func minutesToDate(_ minutes: Int) -> Date {
    Calendar.current.date(
        bySettingHour: minutes / 60,
        minute: minutes % 60,
        second: 0,
        of: Date()
    ) ?? Date()
}

private func dateToMinutes(_ date: Date) -> Int {
    let c = Calendar.current.dateComponents([.hour, .minute], from: date)
    return (c.hour ?? 0) * 60 + (c.minute ?? 0)
}
