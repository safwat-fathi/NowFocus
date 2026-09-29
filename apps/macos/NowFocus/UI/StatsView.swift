import SwiftUI
import NowFocusCore

/// Every number here traces to a real row in `focusSession`/`sessionEvent` —
/// no hardcoded figures. "Turned away" counts blocked *apps* only: `/etc/hosts`
/// sinkholing can't observe a hit, so there is no real per-website count.
struct StatsView: View {
    @State private var weekSessions: [FocusSession] = []
    @State private var streak = 0
    @State private var weekBuckets: [Double] = Array(repeating: 0, count: 7)
    @State private var topBlocked: [(name: String, count: Int)] = []

    private let calendar = HistoryStats.mondayFirstCalendar()
    private let dayLabels = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: NowFocusSpace.s6) {
                header
                HStack(alignment: .top, spacing: NowFocusSpace.s8) {
                    focusColumn
                    statsColumn
                }
            }
            .padding(NowFocusSpace.s6)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .onAppear(perform: load)
    }

    private var header: some View {
        HStack {
            Text("This week")
                .font(NowFocusFonts.heading(28))
                .foregroundColor(NowFocusColors.ink)
            Spacer()
            Text(weekRangeLabel)
                .font(NowFocusFonts.body(13))
                .foregroundColor(NowFocusColors.neutral700)
        }
    }

    private var focusColumn: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s2) {
            HStack(alignment: .firstTextBaseline, spacing: NowFocusSpace.s2) {
                Text(hoursLabel)
                    .font(NowFocusFonts.heading(48))
                Text("focused")
                    .font(NowFocusFonts.body(15))
                    .foregroundColor(NowFocusColors.neutral700)
            }
            .foregroundColor(NowFocusColors.ink)

            HStack(alignment: .bottom, spacing: NowFocusSpace.s3) {
                ForEach(0..<7, id: \.self) { index in
                    VStack(spacing: 4) {
                        Text(weekBuckets[index] > 0 ? "\(Int(weekBuckets[index]))m" : "")
                            .font(NowFocusFonts.body(10))
                            .foregroundColor(NowFocusColors.neutral700)
                        Rectangle()
                            .fill(NowFocusColors.ink)
                            .frame(maxWidth: .infinity)
                            .frame(height: barHeight(for: weekBuckets[index]))
                    }
                }
            }
            .frame(height: 160, alignment: .bottom)

            NowFocusRule(thick: true)

            HStack(spacing: NowFocusSpace.s3) {
                ForEach(0..<7, id: \.self) { index in
                    Text(dayLabels[index])
                        .font(NowFocusFonts.body(11))
                        .foregroundColor(NowFocusColors.neutral700)
                        .frame(maxWidth: .infinity)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var statsColumn: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s4) {
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], alignment: .leading, spacing: 0) {
                statCell("Sessions", "\(weekSessions.count)")
                statCell("Completed", "\(Int(HistoryStats.completionRate(weekSessions) * 100))%")
                statCell("Turned away", "\(topBlocked.reduce(0) { $0 + $1.count })")
                statCell("Streak", "\(streak) day\(streak == 1 ? "" : "s")")
            }
            .overlay(alignment: .top) { NowFocusRule(thick: true) }

            Text("MOST TURNED AWAY")
                .font(NowFocusFonts.body(11).weight(.semibold))
                .tracking(1.0)
                .foregroundColor(NowFocusColors.neutral700)

            if topBlocked.isEmpty {
                Text("Nothing turned away this week.")
                    .font(NowFocusFonts.body(13))
                    .foregroundColor(NowFocusColors.neutral700)
            } else {
                VStack(spacing: 0) {
                    ForEach(Array(topBlocked.enumerated()), id: \.offset) { _, item in
                        HStack {
                            Text(item.name)
                                .font(NowFocusFonts.body(14).weight(.semibold))
                                .foregroundColor(NowFocusColors.ink)
                            Spacer()
                            Text("\(item.count)")
                                .font(NowFocusFonts.body(13))
                                .foregroundColor(NowFocusColors.neutral700)
                        }
                        .padding(.vertical, NowFocusSpace.s2)
                        .overlay(alignment: .bottom) { NowFocusRule() }
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func statCell(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label.uppercased())
                .font(NowFocusFonts.body(11).weight(.semibold))
                .tracking(0.8)
                .foregroundColor(NowFocusColors.neutral700)
            Text(value)
                .font(NowFocusFonts.heading(26))
                .foregroundColor(NowFocusColors.ink)
        }
        .padding(.vertical, NowFocusSpace.s3)
        .padding(.trailing, NowFocusSpace.s3)
        .overlay(alignment: .bottom) { NowFocusRule() }
    }

    private func barHeight(for minutes: Double) -> CGFloat {
        let maxMinutes = max(weekBuckets.max() ?? 0, 1)
        return max(2, CGFloat(minutes / maxMinutes) * 130)
    }

    private var hoursLabel: String {
        let totalMinutes = weekBuckets.reduce(0, +)
        return "\(Int(totalMinutes) / 60)h \(Int(totalMinutes) % 60)m"
    }

    private var weekRangeLabel: String {
        guard let week = calendar.dateInterval(of: .weekOfYear, for: Date()) else { return "" }
        let formatter = DateFormatter()
        formatter.dateFormat = "MMM d"
        let end = calendar.date(byAdding: .day, value: -1, to: week.end) ?? week.end
        return "\(formatter.string(from: week.start)) – \(formatter.string(from: end))"
    }

    private func load() {
        let now = Date()
        guard let week = calendar.dateInterval(of: .weekOfYear, for: now),
              let historyWindowStart = calendar.date(byAdding: .day, value: -90, to: now) else { return }

        do {
            // .focus only — a nightly .bedtime_winddown session would otherwise
            // dominate focused time and keep the streak alive every day.
            let recentSessions = try DatabaseManager.shared.fetchSessions(from: historyWindowStart, to: now.addingTimeInterval(1), sessionType: .focus)
            weekSessions = recentSessions.filter { $0.startAt >= week.start && $0.startAt < week.end }
            weekBuckets = HistoryStats.weekBucketsMinutes(recentSessions, anyDayInWeek: now, calendar: calendar)
            streak = HistoryStats.currentStreakDays(recentSessions, today: now, calendar: calendar)

            let events = try DatabaseManager.shared.fetchEvents(from: week.start, to: week.end, type: "app_blocked")
            topBlocked = HistoryStats.topBlockedApps(events)
        } catch {
            print("Failed to load stats: \(error)")
        }
    }
}
