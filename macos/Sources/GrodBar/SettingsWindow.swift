import AppKit
import SwiftUI

@MainActor
final class SettingsWindow {
    let model: SettingsModel
    private let phone: PhoneMonitor
    private var window: NSWindow?
    private var closeObserver: NSObjectProtocol?

    init(model: SettingsModel, phone: PhoneMonitor) {
        self.model = model
        self.phone = phone
        phone.changed = { [weak model] status in model?.phone = status }
    }

    func show() {
        let window = window ?? make()
        self.window = window
        model.refresh()
        // A menu bar app is never active on its own; the window needs the app in front to take keys.
        NSApp.activate()
        window.makeKeyAndOrderFront(nil)
        // No text field takes the keyboard on open; the window starts at the top, nothing preselected.
        window.makeFirstResponder(nil)
        // adb is polled for the phone's status only while someone is looking at it.
        phone.start()
    }

    private func make() -> NSWindow {
        let window = NSWindow(contentViewController: NSHostingController(rootView: SettingsView(model: model)))
        window.title = "grod Settings"
        window.styleMask = [.titled, .closable, .miniaturizable, .resizable]
        window.titlebarAppearsTransparent = true
        window.backgroundColor = NSColor(GrodColor.background)
        window.isReleasedWhenClosed = false
        window.setContentSize(NSSize(width: 520, height: 640))
        window.contentMinSize = NSSize(width: 440, height: 360)
        window.setFrameAutosaveName("GrodSettings")
        if !window.setFrameUsingName("GrodSettings") { window.center() }
        closeObserver = NotificationCenter.default.addObserver(forName: NSWindow.willCloseNotification, object: window, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.phone.stop() }
        }
        return window
    }
}
