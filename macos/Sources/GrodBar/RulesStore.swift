import Foundation
import GrodCore

@MainActor
final class RulesStore {
    let url: URL
    private(set) var snapshot: RulesSnapshot?
    private(set) var error: String?
    var changed: (() -> Void)?
    private var stamp: Stamp?
    private var timer: Timer?

    private struct Stamp: Equatable {
        let modified: Date?
        let size: UInt64?
    }

    init(url: URL) {
        self.url = url
        reload()
        timer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.poll() }
        }
    }

    private func poll() {
        do {
            let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
            let current = Stamp(modified: attributes[.modificationDate] as? Date,
                                size: attributes[.size] as? UInt64)
            if current != stamp { reload() }
        } catch {
            self.error = error.localizedDescription
            stamp = nil
            changed?()
        }
    }

    func reload() {
        do {
            let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
            let candidate = try RulesSnapshot(data: Data(contentsOf: url))
            snapshot = candidate
            stamp = Stamp(modified: attributes[.modificationDate] as? Date,
                          size: attributes[.size] as? UInt64)
            error = nil
        } catch {
            self.error = error.localizedDescription
        }
        changed?()
    }
}
