import SwiftUI
import AppKit
import NowFocusCore

/// What the block screen shows, gathered each time it appears so the rotation and counts are current.
struct BlockOverlayContent {
    var endAt: Date?
    /// What NowFocus closed, and which rule did it (the screen says both, by name).
    var appName: String?
    var reason: BlockReason
    var triesToday: Int
    var person: UserConnection?
    /// Only set when there is no one to reach out to.
    var goal: String?

    static func load(endAt: Date?, appName: String?, reason: BlockReason) -> BlockOverlayContent {
        let db = DatabaseManager.shared
        let now = Date()
        let events = (try? db.fetchEvents(from: Calendar.current.startOfDay(for: now), to: now, type: "app_blocked")) ?? []
        let person = try? db.fetchNextConnection()
        return BlockOverlayContent(
            endAt: endAt,
            appName: appName,
            reason: reason,
            triesToday: HistoryStats.turnedAwayCount(events),
            person: person,
            goal: person == nil ? (try? db.fetchRandomGoal())?.text : nil
        )
    }
}

enum ReachKind { case call, text }

/// Stamps "talked just now" before handing off, so a tap that never becomes a call still rotates to
/// someone else next time (same rule as Android). What tel:/sms: opens is up to the Mac: FaceTime and
/// Messages may need an iPhone or text forwarding, which is why the number is always shown too.
enum ReachOut {
    static func perform(_ person: UserConnection, _ kind: ReachKind) {
        try? DatabaseManager.shared.markTalked(connectionId: person.id)
        guard let phone = person.phoneNumber,
              let url = PhoneLink.url(scheme: kind == .call ? "tel" : "sms", phone: phone) else { return }
        NSWorkspace.shared.open(url)
    }

    static func copyNumber(_ person: UserConnection) {
        guard let phone = person.phoneNumber else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(phone, forType: .string)
    }
}

/// The full-screen "This can wait." shown over a blocked app. Same copy and shape as Android's block screen.
struct BlockOverlayView: View {
    let content: BlockOverlayContent
    let onQuit: () -> Void
    let onNeedIt: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: NowFocusSpace.s3) {
            Text(BlockCopy.title(appName: content.appName).uppercased())
                .font(NowFocusFonts.body(11).weight(.semibold))
                .nfTracking(1.1)
                .foregroundColor(NowFocusColors.neutral700)

            Text("This can wait.")
                .font(NowFocusFonts.heading(48))
                .foregroundColor(NowFocusColors.ink)

            if let endAt = content.endAt {
                Text(BlockCopy.reason(content.reason, until: endAt.formatted(Date.FormatStyle(date: .omitted, time: .shortened).locale(AppLanguage.locale)), appName: content.appName))
                    .font(NowFocusFonts.body(15))
                    .foregroundColor(NowFocusColors.neutral800)
            }

            VStack(spacing: 0) {
                sideRow(BlockCopy.timeLabel(content.reason)) {
                    if let endAt = content.endAt {
                        // The range's lower bound can't pass its upper one, so clamp if the session just ended.
                        Text(timerInterval: min(Date(), endAt)...endAt, countsDown: true)
                            .monospacedDigit()
                    } else {
                        Text("—")
                    }
                }
                sideRow(String(localized: "Tries today")) { Text("\(content.triesToday)") }
            }
            .padding(.vertical, NowFocusSpace.s2)

            if let person = content.person {
                reachOutCard(person)
            } else if let goal = content.goal {
                VStack(alignment: .leading, spacing: NowFocusSpace.s1) {
                    Text("REMEMBER")
                        .font(NowFocusFonts.body(11).weight(.semibold))
                        .nfTracking(1.1)
                        .foregroundColor(NowFocusColors.neutral700)
                    Text(goal)
                        .font(NowFocusFonts.body(18).weight(.semibold))
                        .foregroundColor(NowFocusColors.ink)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }

            HStack(spacing: NowFocusSpace.s3) {
                // "Quit App" asks the blocked app to quit cooperatively (terminate, never force): it can
                // still decline, e.g. with a save prompt.
                NowFocusPrimaryButton(title: "Quit App", action: onQuit)
                    .frame(width: 180)
                NowFocusGhostButton(title: "I really need it", action: onNeedIt)
            }
            .padding(.top, NowFocusSpace.s2)
        }
        .frame(maxWidth: 480, alignment: .leading)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func sideRow<V: View>(_ label: String, @ViewBuilder value: () -> V) -> some View {
        HStack {
            Text(label.uppercased())
                .font(NowFocusFonts.body(11).weight(.semibold))
                .nfTracking(1.1)
                .foregroundColor(NowFocusColors.neutral700)
            Spacer()
            value()
                .font(NowFocusFonts.body(15).weight(.semibold))
                .foregroundColor(NowFocusColors.ink)
        }
        .padding(.vertical, NowFocusSpace.s1)
    }

    @ViewBuilder
    private func reachOutCard(_ person: UserConnection) -> some View {
        let since = person.sinceLabel(now: Date())
        VStack(alignment: .leading, spacing: NowFocusSpace.s2) {
            Text("OR REACH OUT INSTEAD")
                .font(NowFocusFonts.body(11).weight(.semibold))
                .nfTracking(1.1)
                .foregroundColor(NowFocusColors.neutral700)
            Text(since.map { String(localized: "You haven't talked to \(person.name) in about \($0).") } ?? String(localized: "Reach out to \(person.name) instead."))
                .font(NowFocusFonts.body(19).weight(.semibold))
                .foregroundColor(NowFocusColors.ink)
                .fixedSize(horizontal: false, vertical: true)
            if let phone = person.phoneNumber {
                Text(phone)
                    .font(NowFocusFonts.body(13))
                    .foregroundColor(NowFocusColors.neutral700)
            }
            HStack(spacing: NowFocusSpace.s2) {
                NowFocusSecondaryButton(title: "Call") { ReachOut.perform(person, .call) }
                NowFocusSecondaryButton(title: "Text") { ReachOut.perform(person, .text) }
                NowFocusSecondaryButton(title: "Copy number") { ReachOut.copyNumber(person) }
            }
        }
    }
}

/// The overlay panel never becomes key (see BlockOverlayPanel), so the first click must act, not just focus.
final class FirstMouseHostingView<Content: View>: NSHostingView<Content> {
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
}
