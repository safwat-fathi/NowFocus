import SwiftUI

/// "Modernist" design-system tokens — mirrors Android's `Theme.kt` and
/// Windows' `theme.css`, both ported from the same claude.ai/design project.
/// macOS had none of this before; this pass introduces the tokens
/// themselves and registers Archivo, but doesn't yet re-skin existing
/// views — see the brand-import plan for scope.
enum NowFocusColors {
    static let ink = Color(hex: "#201E1D")
    static let accent = Color(hex: "#EC3013")
    static let ground = Color(hex: "#F3F2F2")
    static let white = Color(hex: "#FFFFFF")

    static let neutral100 = Color(hex: "#F8F4F4")
    static let neutral200 = Color(hex: "#EAE7E7")
    static let neutral300 = Color(hex: "#D7D3D3")
    static let neutral400 = Color(hex: "#BAB6B6")
    static let neutral500 = Color(hex: "#9B9797")
    static let neutral600 = Color(hex: "#7D7979")
    static let neutral700 = Color(hex: "#605D5D")
    static let neutral800 = Color(hex: "#444141")
    static let neutral900 = Color(hex: "#2D2B2B")

    static let accent100 = Color(hex: "#FFF2EF")
    static let accent200 = Color(hex: "#FFE0D9")
    static let accent300 = Color(hex: "#FFC4B8")
    static let accent400 = Color(hex: "#FF9783")
    static let accent500 = Color(hex: "#FF563C")
    static let accent600 = Color(hex: "#DD2B0F")
    static let accent700 = Color(hex: "#AE1800")
    static let accent800 = Color(hex: "#7C1405")
    static let accent900 = Color(hex: "#4D170E")
}

private extension Color {
    init(hex: String) {
        var h = hex
        if h.hasPrefix("#") { h.removeFirst() }
        var v: UInt64 = 0
        Scanner(string: h).scanHexInt64(&v)
        self.init(
            red: Double((v >> 16) & 0xFF) / 255,
            green: Double((v >> 8) & 0xFF) / 255,
            blue: Double(v & 0xFF) / 255
        )
    }
}

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
