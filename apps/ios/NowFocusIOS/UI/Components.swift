import SwiftUI

// iOS-only pieces of the Modernist design system. The tokens and the core components (buttons, rule,
// tag pill, segmented control) come from apps/macos/NowFocus/NowFocusTokens.swift.

struct Kicker: View {
    let text: String
    var body: some View {
        Text(text.uppercased())
            .font(NowFocusFonts.body(11).weight(.semibold)).tracking(1.1)
            .foregroundColor(NowFocusColors.accent700)
    }
}

/// Every non-full-screen page: optional Back, heading, thick rule, scrolling content.
struct ScreenScaffold<Content: View>: View {
    var title: String?
    var onBack: (() -> Void)?
    @ViewBuilder var content: Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: NowFocusSpace.s3) {
                if let onBack { NowFocusGhostButton(title: "‹ Back", action: onBack) }
                if let title {
                    Text(title).font(NowFocusFonts.heading(28))
                    NowFocusRule(thick: true)
                }
                content
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, NowFocusSpace.s4)
            .padding(.vertical, NowFocusSpace.s2)
        }
    }
}

struct BodyText: View {
    let text: String
    var small = false
    var body: some View {
        Text(text).font(NowFocusFonts.body(small ? 13 : 15)).foregroundColor(small ? NowFocusColors.neutral700 : NowFocusColors.ink)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// A tappable list row with title, optional detail and a chevron, closed by a rule.
struct RuleRow: View {
    let title: String
    var detail: String?
    var action: (() -> Void)?

    var body: some View {
        VStack(spacing: 0) {
            Button { action?() } label: {
                HStack {
                    Text(title).font(NowFocusFonts.body(15).weight(.semibold))
                    Spacer()
                    if let detail { Text(detail).font(NowFocusFonts.body(13)).foregroundColor(NowFocusColors.neutral700) }
                    if action != nil { Text("›").foregroundColor(NowFocusColors.neutral700) }
                }
                .padding(.vertical, NowFocusSpace.s3)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            NowFocusRule()
        }
    }
}

struct StatCell: View {
    let label: String
    let value: String
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value).font(NowFocusFonts.heading(22))
            Text(label).font(NowFocusFonts.body(12)).foregroundColor(NowFocusColors.neutral700)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(NowFocusSpace.s2)
        .overlay(Rectangle().stroke(NowFocusColors.divider, lineWidth: 1))
    }
}

struct Chip: View {
    let label: String
    let selected: Bool
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(label)
                .font(NowFocusFonts.body(13).weight(.semibold))
                .foregroundColor(selected ? .white : NowFocusColors.ink)
                .padding(.horizontal, NowFocusSpace.s3).padding(.vertical, NowFocusSpace.s2)
                .background(selected ? NowFocusColors.accent : Color.clear)
                .overlay(Rectangle().stroke(NowFocusColors.divider, lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}

/// Rectangular on/off switch (the design has no pill toggle).
struct NFToggle: View {
    let label: String
    @Binding var isOn: Bool
    var body: some View {
        Button { isOn.toggle() } label: {
            ZStack(alignment: isOn ? .trailing : .leading) {
                Rectangle().fill(isOn ? NowFocusColors.accent : NowFocusColors.neutral300).frame(width: 46, height: 26)
                Rectangle().fill(Color.white).frame(width: 18, height: 18).padding(4)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        .accessibilityValue(isOn ? "On" : "Off")
    }
}

struct FlowChips<Item: Hashable>: View {
    let items: [Item]
    let label: (Item) -> String
    let isSelected: (Item) -> Bool
    let onTap: (Item) -> Void
    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 84), spacing: NowFocusSpace.s2, alignment: .leading)], alignment: .leading, spacing: NowFocusSpace.s2) {
            ForEach(items, id: \.self) { item in
                Chip(label: label(item), selected: isSelected(item)) { onTap(item) }
            }
        }
    }
}

struct FieldStyle: ViewModifier {
    func body(content: Content) -> some View {
        content
            .font(NowFocusFonts.body(15))
            .padding(NowFocusSpace.s3)
            .overlay(Rectangle().stroke(NowFocusColors.divider, lineWidth: 1))
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
    }
}
extension View { func nfField() -> some View { modifier(FieldStyle()) } }

enum Fmt {
    /// "1h 25m" / "25m" / "0m"
    static func focused(_ seconds: TimeInterval) -> String {
        let m = Int(seconds / 60)
        return m >= 60 ? "\(m / 60)h \(m % 60)m" : "\(m)m"
    }

    static func clock(_ minuteOfDay: Int) -> String {
        let date = Calendar.current.date(bySettingHour: minuteOfDay / 60, minute: minuteOfDay % 60, second: 0, of: Date()) ?? Date()
        return date.formatted(date: .omitted, time: .shortened)
    }
}

struct BottomTabBar: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let s = model.screen
        let rules: Bool = { if case .editPolicy = s { return true }; return s == .policies }()
        HStack(spacing: 0) {
            tab("Focus", selected: !rules && s != .devices && s != .stats) { model.screen = model.running ? .active : .home }
            tab("Rules", selected: rules) { model.screen = .policies }
            tab("Devices", selected: s == .devices) { model.screen = .devices }
            tab("Stats", selected: s == .stats) { model.screen = .stats }
        }
        .padding(.horizontal, NowFocusSpace.s2)
    }

    private func tab(_ label: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label)
                .font(NowFocusFonts.body(11).weight(.semibold)).tracking(0.6)
                .foregroundColor(selected ? .white : NowFocusColors.ink)
                .frame(maxWidth: .infinity).padding(.vertical, NowFocusSpace.s3)
                .background(selected ? NowFocusColors.accent : Color.clear)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
