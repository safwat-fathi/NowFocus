import SwiftUI
import NowFocusCore

/// Schedules a nightly `.locked` focus session (see BedtimeScheduler) — not
/// in macOS's original phase scope, but the shared model already anticipated
/// it (`SessionType.bedtime_winddown`, `EnforcementMode.locked`). Ships 1 of
/// the mockup's 4 toggles (lock); DND and "close the feeds" are
/// dropped, same reasoning as Android's BedtimeSchedule.kt.
struct BedtimeView: View {
    @State private var settings = BedtimeSettingsStore.shared.settings
    @State private var policies: [BlockPolicy] = []
    private let lockAvailability = BedtimeLockAvailability.check()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: NowFocusSpace.s4) {
                Text("Bedtime Wind-Down")
                    .font(NowFocusFonts.heading(28))
                    .foregroundColor(NowFocusColors.ink)
                Text("Schedules a locked focus session every night using the profile below. Only runs while NowFocus is open.")
                    .font(NowFocusFonts.body(13))
                    .foregroundColor(NowFocusColors.neutral700)
                    .fixedSize(horizontal: false, vertical: true)

                NowFocusRule(thick: true)

                Toggle(isOn: enabledBinding) {
                    Text("Enabled").font(NowFocusFonts.body(14).weight(.semibold))
                }
                .disabled(settings.policyId == nil)

                if settings.policyId == nil {
                    Text("Choose a profile below before enabling.")
                        .font(NowFocusFonts.body(12))
                        .foregroundColor(NowFocusColors.accent700)
                }

                sectionLabel("Profile")
                Picker("", selection: policyBinding) {
                    Text("None").tag(Optional<String>.none)
                    ForEach(policies) { policy in
                        Text(policy.name).tag(Optional(policy.id))
                    }
                }
                .pickerStyle(.menu)
                .labelsHidden()

                sectionLabel("Schedule")
                timeRow("Wind-down", binding: timeBinding(\.windDownMinute))
                timeRow("Sleep", binding: timeBinding(\.sleepMinute))
                timeRow("Wake", binding: timeBinding(\.wakeMinute))

                sectionLabel("During wind-down")
                Toggle(isOn: lockAtSleepBinding) {
                    Text("Sleep the display (locks if Lock Screen requires a password immediately)")
                        .font(NowFocusFonts.body(14))
                }
                if !lockAvailability.available, let reason = lockAvailability.reason {
                    Text(reason)
                        .font(NowFocusFonts.body(12))
                        .foregroundColor(NowFocusColors.accent800)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(NowFocusSpace.s2)
                        .background(NowFocusColors.accent100)
                }
                Text("Do Not Disturb isn't offered here: macOS has no public API for a regular app to toggle it, same reasoning as the domain/app editor's dropped \u{201C}feeds only\u{201D} option.")
                    .font(NowFocusFonts.body(12))
                    .foregroundColor(NowFocusColors.neutral700)
                    .fixedSize(horizontal: false, vertical: true)

                NowFocusRule()
                Text("Disabling Bedtime mid-window doesn't end tonight's already-started locked session.")
                    .font(NowFocusFonts.body(11))
                    .foregroundColor(NowFocusColors.neutral700)
            }
            .padding(NowFocusSpace.s6)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .onAppear(perform: loadPolicies)
        // This view keeps a copy of the settings; if sync replaced them, the next toggle would save the stale copy back.
        .onReceive(NotificationCenter.default.publisher(for: .nowFocusSyncApplied)) { _ in
            settings = BedtimeSettingsStore.shared.settings
            loadPolicies()
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
        .padding(.vertical, NowFocusSpace.s1)
        .overlay(alignment: .bottom) { NowFocusRule() }
    }

    private func sectionLabel(_ text: LocalizedStringResource) -> some View {
        Text(locr(text).uppercased())
            .font(NowFocusFonts.body(11).weight(.semibold))
            .nfTracking(1.0)
            .foregroundColor(NowFocusColors.neutral700)
    }

    private var enabledBinding: Binding<Bool> {
        Binding(get: { settings.enabled }, set: { newValue in
            settings.enabled = newValue
            save()
        })
    }

    private var lockAtSleepBinding: Binding<Bool> {
        Binding(get: { settings.lockAtSleep }, set: { newValue in
            settings.lockAtSleep = newValue
            save()
        })
    }

    private var policyBinding: Binding<String?> {
        Binding(get: { settings.policyId }, set: { newValue in
            settings.policyId = newValue
            save()
        })
    }

    private func timeBinding(_ keyPath: WritableKeyPath<BedtimeSettings, Int>) -> Binding<Date> {
        Binding(
            get: {
                let minutes = settings[keyPath: keyPath]
                return Calendar.current.date(bySettingHour: minutes / 60, minute: minutes % 60, second: 0, of: Date()) ?? Date()
            },
            set: { newDate in
                let components = Calendar.current.dateComponents([.hour, .minute], from: newDate)
                settings[keyPath: keyPath] = (components.hour ?? 0) * 60 + (components.minute ?? 0)
                save()
            }
        )
    }

    private func save() {
        BedtimeSettingsStore.shared.settings = settings
        BedtimeScheduler.shared.start()
    }

    private func loadPolicies() {
        do {
            policies = try DatabaseManager.shared.fetchAllPolicies()
        } catch {
            print("Failed to load profiles for Bedtime: \(error)")
        }
    }
}
