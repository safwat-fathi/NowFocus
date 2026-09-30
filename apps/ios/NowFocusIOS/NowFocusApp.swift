import CoreText
import SwiftUI

@main
struct NowFocusApp: App {
    @State private var model: AppModel

    init() {
        // Archivo (variable) is registered at runtime, same as the macOS app, so there's no static Info.plist entry.
        if let url = Bundle.main.url(forResource: "archivo_variable", withExtension: "ttf") {
            CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
        }
        _model = State(initialValue: AppModel())
    }

    var body: some Scene {
        WindowGroup {
            RootView().environment(model)
        }
    }
}
