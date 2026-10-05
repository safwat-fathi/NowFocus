import Cocoa
import SwiftUI
import NowFocusCore

public class BlockOverlayPanel: NSPanel {
    /// "Quit App": ask the blocked app to quit.
    public var onClose: (() -> Void)?
    /// "I really need it": leave the overlay and open the unlock flow.
    public var onNeedIt: (() -> Void)?

    public init() {
        super.init(
            contentRect: .zero,
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )

        self.level = .screenSaver
        self.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        self.backgroundColor = NSColor(NowFocusColors.ground).withAlphaComponent(0.97)
        self.isOpaque = false
        self.hasShadow = false
    }

    // This panel is borderless and non-activating, so it never becomes key, on purpose: forcing
    // canBecomeKey would swallow Cmd+Q for the blocked app underneath, which is the one escape hatch
    // that already works. That's why "I really need it" opens the unlock flow in its own window
    // rather than asking for typing in here.
    public func show(over rect: NSRect, endAt: Date?, appName: String?, reason: BlockReason) {
        let view = BlockOverlayView(
            content: BlockOverlayContent.load(endAt: endAt, appName: appName, reason: reason),
            onQuit: { [weak self] in self?.onClose?() },
            onNeedIt: { [weak self] in self?.onNeedIt?() }
        )
        self.contentView = FirstMouseHostingView(rootView: view.environment(\.locale, AppLanguage.locale))
        self.setFrame(rect, display: true)
        self.makeKeyAndOrderFront(nil)
    }

    public func hide() {
        self.orderOut(nil)
    }
}
