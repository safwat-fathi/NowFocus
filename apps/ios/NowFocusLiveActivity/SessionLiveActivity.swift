import ActivityKit
import SwiftUI
import WidgetKit

@main
struct NowFocusLiveActivityBundle: WidgetBundle {
    var body: some Widget { SessionLiveActivity() }
}

struct SessionLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: SessionActivityAttributes.self) { context in
            VStack(alignment: .leading, spacing: 4) {
                Text("FOCUS SESSION · \(context.state.mode.uppercased())")
                    .font(.system(size: 11, weight: .semibold)).tracking(1.1)
                    .foregroundStyle(.white.opacity(0.85))
                Countdown(endAt: context.state.endAt).font(.system(size: 44, weight: .black))
                Text(context.attributes.policyName)
                    .font(.system(size: 13)).foregroundStyle(.white.opacity(0.85))
            }
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .activityBackgroundTint(Color(red: 0.925, green: 0.188, blue: 0.075))
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) { Text("Focus").font(.system(size: 15, weight: .heavy)) }
                DynamicIslandExpandedRegion(.trailing) { Countdown(endAt: context.state.endAt).font(.system(size: 20, weight: .heavy)) }
                DynamicIslandExpandedRegion(.bottom) { Text(context.attributes.policyName).font(.system(size: 13)) }
            } compactLeading: {
                Image(systemName: "scope")
            } compactTrailing: {
                Countdown(endAt: context.state.endAt).frame(width: 52)
            } minimal: {
                Image(systemName: "scope")
            }
        }
    }
}

/// Counts down on its own (no updates pushed); clamped so a past `endAt` shows 0:00 instead of trapping.
private struct Countdown: View {
    let endAt: Date
    var body: some View {
        Text(timerInterval: Date.now...max(endAt, Date.now), countsDown: true)
            .monospacedDigit().multilineTextAlignment(.leading)
    }
}
