import Cocoa

public class BlockOverlayPanel: NSPanel {
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
        
        let container = NSView()
        container.addSubview(textLabel)
        
        textLabel.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            textLabel.centerXAnchor.constraint(equalTo: container.centerXAnchor),
            textLabel.centerYAnchor.constraint(equalTo: container.centerYAnchor)
        ])
        
        self.contentView = container
    }
    
    public func show(over rect: NSRect) {
        self.setFrame(rect, display: true)
        self.makeKeyAndOrderFront(nil)
    }
    
    public func hide() {
        self.orderOut(nil)
    }
}
