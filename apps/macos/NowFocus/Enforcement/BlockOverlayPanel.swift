import Cocoa
import SwiftUI
import NowFocusCore

public class BlockOverlayPanel: NSPanel {
    public var onClose: (() -> Void)?
    private var motivationLabel: NSTextField?

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

        setupUI()
    }

    private func setupUI() {
        let textLabel = NSTextField(labelWithString: "This application is blocked by NowFocus")
        // "ArchivoRoman-Black" — the variable font's named ExtraBold/Black
        // instance (confirmed present via NSFontManager at launch); NSFont(name:)
        // needs the exact PostScript name, unlike SwiftUI's Font.custom().weight().
        textLabel.font = NSFont(name: "ArchivoRoman-Black", size: 24) ?? .systemFont(ofSize: 24, weight: .bold)
        textLabel.textColor = NSColor(NowFocusColors.ink)
        textLabel.alignment = .center

        // "Quit App", not an auto-close claim — clicking this asks the blocked
        // app to quit cooperatively (see closeBlockedApp/terminate(), not
        // forceTerminate); the app can still decline (e.g. a save prompt).
        let quitButton = NSButton(title: "Quit App", target: self, action: #selector(quitButtonTapped))
        quitButton.controlSize = .large
        // Not wired as the window's default button (Return) — this panel is
        // borderless and non-activating, so it can never become key; Return
        // would silently do nothing, and forcing canBecomeKey would also
        // swallow Cmd+Q for the blocked app underneath, which is the one
        // escape hatch that already works today.
        //
        // Native bezelColor is a no-op here: AppKit desaturates a button's
        // bezel/title in any non-key window, and this panel is permanently
        // non-key by design above — so a plain NSButton would always render
        // washed-out regardless of bezelColor. Paint the accent fill and title
        // color ourselves via a layer so it isn't tied to key-window state.
        quitButton.isBordered = false
        quitButton.wantsLayer = true
        quitButton.layer?.backgroundColor = NSColor(NowFocusColors.accent).cgColor
        quitButton.attributedTitle = NSAttributedString(
            string: "Quit App",
            attributes: [
                .foregroundColor: NSColor(NowFocusColors.ground),
                .font: NSFont.systemFont(ofSize: 15, weight: .semibold)
            ]
        )
        quitButton.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            quitButton.widthAnchor.constraint(equalToConstant: 160),
            quitButton.heightAnchor.constraint(equalToConstant: 44)
        ])

        // Motivation label — refreshed each time show() is called.
        let motivationLabel = NSTextField(labelWithString: "")
        motivationLabel.font = NSFont(name: "Archivo", size: 15) ?? .systemFont(ofSize: 15)
        motivationLabel.textColor = NSColor(NowFocusColors.neutral700)
        motivationLabel.alignment = .center
        motivationLabel.isHidden = true
        motivationLabel.maximumNumberOfLines = 2
        self.motivationLabel = motivationLabel

        let stack = NSStackView(views: [textLabel, motivationLabel, quitButton])
        stack.orientation = .vertical
        stack.alignment = .centerX
        stack.spacing = 20

        let container = NSView()
        container.addSubview(stack)

        stack.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            stack.centerXAnchor.constraint(equalTo: container.centerXAnchor),
            stack.centerYAnchor.constraint(equalTo: container.centerYAnchor)
        ])

        self.contentView = container
    }

    @objc private func quitButtonTapped() {
        onClose?()
    }

    public func show(over rect: NSRect) {
        refreshMotivationLabel()
        self.setFrame(rect, display: true)
        self.makeKeyAndOrderFront(nil)
    }

    private func refreshMotivationLabel() {
        guard let label = motivationLabel else { return }
        // Prefer a connection with a phone number; fall back to goal.
        if let conn = try? DatabaseManager.shared.fetchRandomConnection() {
            if let phone = conn.phoneNumber, !phone.isEmpty {
                label.stringValue = "Call \(conn.name) instead (\'\(phone)\')."
            } else {
                label.stringValue = "Think of \(conn.name)."
            }
            label.isHidden = false
        } else if let goal = try? DatabaseManager.shared.fetchRandomGoal() {
            label.stringValue = "Remember: \(goal.text)"
            label.isHidden = false
        } else {
            label.isHidden = true
        }
    }
    
    public func hide() {
        self.orderOut(nil)
    }
}
