import AppKit
import GrodCore

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    private var statusItem: NSStatusItem!
    private var store: RulesStore!
    private var server: LocalServer!
    private var safari: SafariGuard!
    private var runtimeError: String?
    private var observers: [NSObjectProtocol] = []

    func applicationDidFinishLaunching(_ notification: Notification) {
        let path = ProcessInfo.processInfo.environment["GROD_RULES"]
            ?? Bundle.main.object(forInfoDictionaryKey: "GrodRulesPath") as? String
        guard let path, !path.isEmpty else {
            statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
            statusItem.button?.image = Self.mark()
            let menu = NSMenu()
            menu.addItem(withTitle: "Missing GrodRulesPath / GROD_RULES", action: nil, keyEquivalent: "")
            let quit = menu.addItem(withTitle: "Quit", action: #selector(quit), keyEquivalent: "q")
            quit.target = self
            statusItem.menu = menu
            return
        }
        store = RulesStore(url: URL(fileURLWithPath: path))
        safari = SafariGuard(store: store)
        server = LocalServer(store: store)
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        statusItem.button?.image = Self.mark()
        store.changed = { [weak self] in self?.refreshMenu() }
        server.failed = { [weak self] error in self?.report(error) }
        safari.failed = { [weak self] error in self?.report(error) }
        do { try server.start() } catch { report("Local server: \(error.localizedDescription)") }
        let center = NSWorkspace.shared.notificationCenter
        for name in [NSWorkspace.didLaunchApplicationNotification, NSWorkspace.didActivateApplicationNotification] {
            observers.append(center.addObserver(forName: name, object: nil, queue: .main) { [weak self] notification in
                let app = notification.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication
                MainActor.assumeIsolated {
                    guard let self else { return }
                    if let app { self.enforce(app) }
                    if name == NSWorkspace.didActivateApplicationNotification { self.safari.updateTimer() }
                }
            })
        }
        safari.updateTimer()
        refreshMenu()
    }

    /// brand/grod-mark.svg drawn natively: a template image, so macOS tints it for light and dark menu bars.
    private static func mark() -> NSImage {
        let image = NSImage(size: NSSize(width: 18, height: 18), flipped: false) { rect in
            let unit = rect.width / 24
            // brand/grod-mark.svg flipped to AppKit's y-up space: bowl, then stem curving into the tail.
            let g = NSBezierPath(ovalIn: NSRect(x: 7 * unit, y: 10 * unit, width: 10 * unit, height: 10 * unit))
            g.move(to: NSPoint(x: 17 * unit, y: 20 * unit))
            g.line(to: NSPoint(x: 17 * unit, y: 8.5 * unit))
            g.appendArc(withCenter: NSPoint(x: 12 * unit, y: 8.5 * unit), radius: 5 * unit, startAngle: 0, endAngle: 270, clockwise: true)
            g.line(to: NSPoint(x: 9 * unit, y: 3.5 * unit))
            g.lineWidth = 2.4 * unit
            g.lineCapStyle = .round
            NSColor.black.setStroke()
            g.stroke()
            return true
        }
        image.isTemplate = true
        image.accessibilityDescription = "grod"
        return image
    }

    private func enforce(_ app: NSRunningApplication) {
        guard let id = app.bundleIdentifier,
              store.snapshot?.document.rules.contains(where: {
                  $0.enabled && ($0.macos?.quitApps?.contains(id) ?? false)
              }) == true else { return }
        if !app.terminate() { report("Could not quit \(app.localizedName ?? id).") }
    }

    private func report(_ error: String) {
        runtimeError = error
        refreshMenu()
    }

    private func refreshMenu() {
        let menu = NSMenu()
        let revision = store.snapshot?.revision ?? "unavailable"
        let count = store.snapshot?.document.rules.filter(\.enabled).count ?? 0
        menu.addItem(withTitle: "Rules \(revision) · \(count) enabled", action: nil, keyEquivalent: "")
        if let error = store.error ?? runtimeError {
            menu.addItem(withTitle: error, action: nil, keyEquivalent: "")
        }
        menu.addItem(.separator())
        let toggle = menu.addItem(withTitle: "Safari guard: \(safari.enabled ? "on" : "off")", action: #selector(toggleSafari), keyEquivalent: "")
        toggle.state = safari.enabled ? .on : .off
        menu.addItem(withTitle: "Open rules file", action: #selector(openRules), keyEquivalent: "")
        menu.addItem(withTitle: "Reload now", action: #selector(reload), keyEquivalent: "r")
        menu.addItem(.separator())
        menu.addItem(withTitle: "Quit", action: #selector(quit), keyEquivalent: "q")
        for item in menu.items where item.action != nil { item.target = self }
        statusItem.menu = menu
    }

    @objc private func toggleSafari() { safari.enabled.toggle(); refreshMenu() }
    @objc private func openRules() { NSWorkspace.shared.open(store.url) }
    @objc private func reload() { store.reload() }
    @objc private func quit() { NSApplication.shared.terminate(nil) }
}
