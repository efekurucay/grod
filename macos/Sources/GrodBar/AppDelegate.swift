import AppKit
import GrodCore
import UserNotifications

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    private var statusItem: NSStatusItem!
    private var store: RulesStore!
    private var server: LocalServer!
    private var safari: SafariGuard!
    private var review: ReviewWatcher!
    private var settings: SettingsWindow!
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
        let cli = GrodCLI(rulesURL: store.url)
        safari = SafariGuard(store: store)
        server = LocalServer(store: store)
        review = ReviewWatcher(cli: cli)
        let model = SettingsModel(store: store, cli: cli, safariGuard: safari.enabled) { [weak self] enabled in
            self?.safari.enabled = enabled
            self?.refreshMenu()
        }
        settings = SettingsWindow(model: model, phone: PhoneMonitor(store: store, cli: cli))
        settings.model.lastReview = review.lastReview
        NSApp.mainMenu = Self.mainMenu(target: self)
        if Bundle.main.bundleIdentifier != nil { UNUserNotificationCenter.current().delegate = self }
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        statusItem.button?.image = Self.mark()
        store.changed = { [weak self] in
            self?.refreshMenu()
            self?.settings.model.refresh()
        }
        server.failed = { [weak self] error in self?.report(error) }
        safari.failed = { [weak self] error in self?.report(error) }
        review.failed = { [weak self] error in self?.report(error) }
        review.changed = { [weak self] in
            guard let self else { return }
            settings.model.lastReview = review.lastReview
            refreshMenu()
        }
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
        menu.addItem(withTitle: "Settings…", action: #selector(showSettings), keyEquivalent: ",")
        let toggle = menu.addItem(withTitle: "Safari guard: \(safari.enabled ? "on" : "off")", action: #selector(toggleSafari), keyEquivalent: "")
        toggle.state = safari.enabled ? .on : .off
        menu.addItem(withTitle: "Open rules file", action: #selector(openRules), keyEquivalent: "")
        menu.addItem(withTitle: "Reload now", action: #selector(reload), keyEquivalent: "r")
        menu.addItem(.separator())
        let last = review.lastReview.map { $0.formatted(date: .abbreviated, time: .omitted) } ?? "never"
        menu.addItem(withTitle: "Last review: \(last)", action: nil, keyEquivalent: "")
        menu.addItem(withTitle: "Review with Claude…", action: #selector(openReview), keyEquivalent: "")
        menu.addItem(.separator())
        menu.addItem(withTitle: "Quit", action: #selector(quit), keyEquivalent: "q")
        for item in menu.items where item.action != nil { item.target = self }
        statusItem.menu = menu
    }

    @objc private func toggleSafari() {
        safari.enabled.toggle()
        settings.model.syncSafariGuard(safari.enabled)
        refreshMenu()
    }
    @objc private func showSettings() { settings.show() }
    @objc private func openReview() { review.openReview() }
    @objc private func openRules() { NSWorkspace.shared.open(store.url) }
    @objc private func reload() { store.reload() }
    @objc private func quit() { NSApplication.shared.terminate(nil) }
}

extension AppDelegate {
    /// Invisible for a menu bar app, but it carries the key equivalents of the Settings window:
    /// paste into its text fields, close it, open it again.
    fileprivate static func mainMenu(target: AppDelegate) -> NSMenu {
        let main = NSMenu()
        let app = NSMenu()
        app.addItem(withTitle: "Settings…", action: #selector(showSettings), keyEquivalent: ",").target = target
        app.addItem(withTitle: "Quit grod", action: #selector(quit), keyEquivalent: "q").target = target
        let edit = NSMenu(title: "Edit")
        edit.addItem(withTitle: "Undo", action: Selector(("undo:")), keyEquivalent: "z")
        edit.addItem(withTitle: "Redo", action: Selector(("redo:")), keyEquivalent: "Z")
        edit.addItem(withTitle: "Cut", action: #selector(NSText.cut(_:)), keyEquivalent: "x")
        edit.addItem(withTitle: "Copy", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
        edit.addItem(withTitle: "Paste", action: #selector(NSText.paste(_:)), keyEquivalent: "v")
        edit.addItem(withTitle: "Select All", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
        let window = NSMenu(title: "Window")
        window.addItem(withTitle: "Close", action: #selector(NSWindow.performClose(_:)), keyEquivalent: "w")
        for menu in [app, edit, window] {
            main.addItem(withTitle: menu.title, action: nil, keyEquivalent: "").submenu = menu
        }
        return main
    }
}

extension AppDelegate: UNUserNotificationCenterDelegate {
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        await MainActor.run { review.openReview() }
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .list]
    }
}
