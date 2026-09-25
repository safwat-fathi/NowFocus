import Cocoa

public class BlockOverlayPanel: NSPanel {
    public var onClose: (() -> Void)?

    public init() {
        super.init(
            contentRect: .zero,
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        
        self.level = .screenSaver
        self.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        self.backgroundColor = NSColor.windowBackgroundColor.withAlphaComponent(0.95)
        self.isOpaque = false
        self.hasShadow = false
        
        setupUI()
    }
    
    private func setupUI() {
        let textLabel = NSTextField(labelWithString: "This application is blocked by NowFocus")
        textLabel.font = .systemFont(ofSize: 24, weight: .bold)
        textLabel.textColor = .labelColor
        textLabel.alignment = .center
        
        let quitButton = NSButton(title: "Quit App", target: self, action: #selector(quitButtonTapped))
        quitButton.controlSize = .large
        // Tints it like the .borderedProminent buttons used elsewhere in the app.
        // Not wired as the window's default button (Return) — this panel is borderless
        // and non-activating, so it can never become key; Return would silently do
        // nothing, and forcing canBecomeKey would also swallow Cmd+Q for the blocked
        // app underneath, which is the one escape hatch that already works today.
        quitButton.bezelColor = .controlAccentColor

        let stack = NSStackView(views: [textLabel, quitButton])
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
        self.setFrame(rect, display: true)
        self.makeKeyAndOrderFront(nil)
    }
    
    public func hide() {
        self.orderOut(nil)
    }
}
