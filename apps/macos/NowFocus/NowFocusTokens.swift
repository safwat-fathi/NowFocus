import SwiftUI

/// "Modernist" design-system tokens — mirrors Android's `Theme.kt` and
/// Windows' `theme.css`, both ported from the same claude.ai/design project.
/// macOS had none of this before; this pass introduces the tokens
/// themselves and registers Archivo, but doesn't yet re-skin existing
/// views — see the brand-import plan for scope.
enum NowFocusColors {
    static let ink = Color.dynamic(light: "#201E1D", dark: "#F8F4F4")
    static let accent = Color.dynamic(light: "#EC3013", dark: "#FF563C")
    static let ground = Color.dynamic(light: "#F3F2F2", dark: "#201E1D")
    static let white = Color.white

    static let neutral100 = Color.dynamic(light: "#F8F4F4", dark: "#2D2B2B")
    static let neutral200 = Color.dynamic(light: "#EAE7E7", dark: "#444141")
    static let neutral300 = Color.dynamic(light: "#D7D3D3", dark: "#605D5D")
    static let neutral400 = Color.dynamic(light: "#BAB6B6", dark: "#7D7979")
    static let neutral500 = Color.dynamic(light: "#9B9797", dark: "#9B9797")
    static let neutral600 = Color.dynamic(light: "#7D7979", dark: "#BAB6B6")
    static let neutral700 = Color.dynamic(light: "#605D5D", dark: "#D7D3D3")
    static let neutral800 = Color.dynamic(light: "#444141", dark: "#EAE7E7")
    static let neutral900 = Color.dynamic(light: "#2D2B2B", dark: "#F8F4F4")

    static let accent100 = Color.dynamic(light: "#FFF2EF", dark: "#4D170E")
    static let accent200 = Color.dynamic(light: "#FFE0D9", dark: "#7C1405")
    static let accent300 = Color.dynamic(light: "#FFC4B8", dark: "#AE1800")
    static let accent400 = Color.dynamic(light: "#FF9783", dark: "#DD2B0F")
    static let accent500 = Color.dynamic(light: "#FF563C", dark: "#FF563C")
    static let accent600 = Color.dynamic(light: "#DD2B0F", dark: "#FF9783")
    static let accent700 = Color.dynamic(light: "#AE1800", dark: "#FFC4B8")
    static let accent800 = Color.dynamic(light: "#7C1405", dark: "#FFE0D9")
    static let accent900 = Color.dynamic(light: "#4D170E", dark: "#FFF2EF")

    static let divider = ink.opacity(0.4)
}

/// 4/8/12/16/24/32 — mirrors Android's `NowFocusSpace` (same source tokens).
enum NowFocusSpace {
    static let s1: CGFloat = 4
    static let s2: CGFloat = 8
    static let s3: CGFloat = 12
    static let s4: CGFloat = 16
    static let s6: CGFloat = 24
    static let s8: CGFloat = 32
}

#if canImport(UIKit)
import UIKit

private extension UIColor {
    convenience init(hex: String) {
        var h = hex
        if h.hasPrefix("#") { h.removeFirst() }
        var v: UInt64 = 0
        Scanner(string: h).scanHexInt64(&v)
        self.init(
            red: CGFloat((v >> 16) & 0xFF) / 255,
            green: CGFloat((v >> 8) & 0xFF) / 255,
            blue: CGFloat(v & 0xFF) / 255,
            alpha: 1.0
        )
    }
}

private extension Color {
    static func dynamic(light: String, dark: String) -> Color {
        Color(uiColor: UIColor { traits in
            UIColor(hex: traits.userInterfaceStyle == .dark ? dark : light)
        })
    }
}
#else
private extension NSColor {
    convenience init(hex: String) {
        var h = hex
        if h.hasPrefix("#") { h.removeFirst() }
        var v: UInt64 = 0
        Scanner(string: h).scanHexInt64(&v)
        self.init(
            red: CGFloat((v >> 16) & 0xFF) / 255,
            green: CGFloat((v >> 8) & 0xFF) / 255,
            blue: CGFloat(v & 0xFF) / 255,
            alpha: 1.0
        )
    }
}

private extension Color {
    static func dynamic(light: String, dark: String) -> Color {
        Color(nsColor: NSColor(name: nil, dynamicProvider: { appearance in
            if appearance.bestMatch(from: [.aqua, .darkAqua]) == .darkAqua {
                return NSColor(hex: dark)
            }
            return NSColor(hex: light)
        }))
    }
}
#endif

enum NowFocusFonts {
    /// 0 everywhere — the Modernist system's deliberate "no rounded corners" rule.
    static let radius: CGFloat = 0

    static func heading(_ size: CGFloat) -> Font {
        .custom("Archivo", size: size).weight(.heavy)
    }

    static func body(_ size: CGFloat) -> Font {
        .custom("Archivo", size: size)
    }
}

// MARK: - Components
//
// Mirrors apps/android/.../Theme.kt's composables 1:1 — same tokens, same
// component vocabulary, just SwiftUI instead of Compose.

/// Flat divider line — `SectionRule` in Android, `.hr`/section borders in styles.css.
struct NowFocusRule: View {
    var thick: Bool = false
    var vertical: Bool = false

    var body: some View {
        Rectangle()
            .fill(NowFocusColors.divider)
            .frame(width: vertical ? (thick ? 2 : 1) : nil,
                   height: vertical ? nil : (thick ? 2 : 1))
    }
}

/// Full-width solid CTA. Android's `PrimaryButton`.
struct NowFocusPrimaryButton: View {
    let title: String
    var enabled: Bool = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(NowFocusFonts.heading(16))
                .foregroundColor(NowFocusColors.ground)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, NowFocusSpace.s4)
                .padding(.vertical, NowFocusSpace.s3)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .background(enabled ? NowFocusColors.accent : NowFocusColors.neutral400)
        .disabled(!enabled)
    }
}

/// Outlined button. Android's `SecondaryButton`.
struct NowFocusSecondaryButton: View {
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(NowFocusFonts.heading(14))
                .foregroundColor(NowFocusColors.ink)
                .padding(.horizontal, NowFocusSpace.s3)
                .padding(.vertical, NowFocusSpace.s2)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .overlay(Rectangle().stroke(NowFocusColors.divider, lineWidth: 1))
    }
}

/// No background/border, accent-colored text. Android's `GhostButton`.
struct NowFocusGhostButton: View {
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(NowFocusFonts.body(14).weight(.semibold))
                .foregroundColor(NowFocusColors.accent)
                .padding(.vertical, NowFocusSpace.s2)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// Small status pill. Android's `TagPill` — `accent: true` uses the one loud
/// accent voice for "needs attention" (also used for "session running now"),
/// `false` is the neutral/healthy tone. Matches `.tag`/`.tag-accent`/
/// `.tag-neutral` in styles.css.
struct NowFocusTagPill: View {
    let text: String
    var accent: Bool = true

    var body: some View {
        Text(text)
            .font(NowFocusFonts.body(11))
            .tracking(0.2)
            .foregroundColor(accent ? NowFocusColors.accent800 : NowFocusColors.neutral800)
            .padding(.horizontal, 10)
            .padding(.vertical, 3)
            .background(accent ? NowFocusColors.accent100 : NowFocusColors.neutral100)
    }
}

/// Rectangular multi-way selector — no system `.pickerStyle(.segmented)`
/// pill, the design has none. Android's `SegmentedControl`.
struct NowFocusSegmentedControl<T: Hashable>: View {
    let options: [(label: String, value: T)]
    @Binding var selection: T

    var body: some View {
        HStack(spacing: 0) {
            ForEach(Array(options.enumerated()), id: \.offset) { index, option in
                let isSelected = option.value == selection
                Button {
                    selection = option.value
                } label: {
                    Text(option.label)
                        .font(NowFocusFonts.body(13).weight(.semibold))
                        .foregroundColor(isSelected ? NowFocusColors.ground : NowFocusColors.ink)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, NowFocusSpace.s2)
                        .contentShape(Rectangle())
                        .background(isSelected ? NowFocusColors.accent : Color.clear)
                }
                .buttonStyle(.plain)
                .overlay(alignment: .leading) {
                    if index > 0 {
                        Rectangle().fill(NowFocusColors.divider).frame(width: 1)
                    }
                }
            }
        }
        .overlay(Rectangle().stroke(NowFocusColors.divider, lineWidth: 1))
    }
}
