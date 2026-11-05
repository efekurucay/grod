import Foundation
import Network

@MainActor
final class LocalServer {
    private let store: RulesStore
    private var listener: NWListener?
    private var clients: [UUID: NWConnection] = [:]
    var failed: ((String) -> Void)?

    init(store: RulesStore) { self.store = store }

    func start() throws {
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: 47770)
        let listener = try NWListener(using: parameters)
        listener.stateUpdateHandler = { [weak self] state in
            if case .failed(let error) = state {
                Task { @MainActor in self?.failed?("Local server: \(error.localizedDescription)") }
            }
        }
        listener.newConnectionHandler = { [weak self] connection in
            Task { @MainActor in self?.accept(connection) }
        }
        self.listener = listener
        listener.start(queue: .main)
    }

    private func accept(_ connection: NWConnection) {
        let id = UUID()
        clients[id] = connection
        connection.start(queue: .main)
        receive(id, data: Data())
        Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(5))
            self?.close(id)
        }
    }

    private func close(_ id: UUID) {
        clients.removeValue(forKey: id)?.cancel()
    }

    private func receive(_ id: UUID, data: Data) {
        guard let connection = clients[id] else { return }
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16384) { [weak self] chunk, _, complete, error in
            Task { @MainActor in
                guard let self, self.clients[id] != nil else { return }
                var buffer = data
                if let chunk { buffer.append(chunk) }
                if buffer.count > 16384 {
                    self.send(id, status: "431 Request Header Fields Too Large", type: "text/plain", body: Data())
                } else if let end = buffer.range(of: Data("\r\n\r\n".utf8)) {
                    self.handle(id, header: buffer.subdata(in: 0..<end.lowerBound))
                } else if complete || error != nil {
                    self.close(id)
                } else {
                    self.receive(id, data: buffer)
                }
            }
        }
    }

    private func handle(_ id: UUID, header: Data) {
        guard let text = String(data: header, encoding: .utf8) else { badRequest(id); return }
        let lines = text.components(separatedBy: "\r\n")
        let request = lines[0].split(separator: " ", omittingEmptySubsequences: false)
        guard request.count == 3, ["HTTP/1.0", "HTTP/1.1"].contains(request[2]),
              request[1].hasPrefix("/"), !request[1].hasPrefix("//") else { badRequest(id); return }
        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            guard let colon = line.firstIndex(of: ":") else { badRequest(id); return }
            let name = String(line[..<colon]).lowercased()
            guard !name.isEmpty, name.utf8.allSatisfy({ (33...126).contains($0) && !"()<>@,;:\\\"/[]?={} \t".utf8.contains($0) }),
                  headers[name] == nil else { badRequest(id); return }
            headers[name] = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
        }
        guard (request[2] != "HTTP/1.1" || !(headers["host"] ?? "").isEmpty),
              headers["transfer-encoding"] == nil,
              headers["content-length"] == nil || headers["content-length"] == "0",
              let components = URLComponents(string: String(request[1])) else { badRequest(id); return }
        guard request[0] == "GET" else {
            send(id, status: "404 Not Found", type: "text/plain", body: Data("Not found".utf8)); return
        }
        if components.path == "/rules.json" {
            guard let snapshot = store.snapshot else {
                send(id, status: "503 Service Unavailable", type: "text/plain", body: Data("No valid rules loaded".utf8)); return
            }
            send(id, status: "200 OK", type: "application/json", body: snapshot.servedJSON)
        } else if components.path == "/blocked",
                  let ruleID = components.queryItems?.first(where: { $0.name == "rule" })?.value,
                  let rule = store.snapshot?.document.rules.first(where: { $0.id == ruleID }) {
            let html = """
            <!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>grod · \(escape(rule.title))</title><style>:root{color-scheme:light dark}body{font:18px/1.6 system-ui;margin:15vh auto;padding:24px;max-width:36rem}h1{font-size:28px}button{font:inherit;padding:8px 18px;cursor:pointer}</style>
            <main><p>grod</p><h1>\(escape(rule.title))</h1><p>\(escape(rule.why ?? "Take a moment away from the feed."))</p><button onclick="history.back()">Go back</button></main></html>
            """
            send(id, status: "200 OK", type: "text/html; charset=utf-8", body: Data(html.utf8))
        } else {
            send(id, status: "404 Not Found", type: "text/plain", body: Data("Not found".utf8))
        }
    }

    private func badRequest(_ id: UUID) {
        send(id, status: "400 Bad Request", type: "text/plain", body: Data("Bad request".utf8))
    }

    private func send(_ id: UUID, status: String, type: String, body: Data) {
        guard let connection = clients[id] else { return }
        var response = Data("HTTP/1.1 \(status)\r\nContent-Type: \(type)\r\nContent-Length: \(body.count)\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".utf8)
        response.append(body)
        connection.send(content: response, completion: .contentProcessed { [weak self] _ in
            Task { @MainActor in self?.close(id) }
        })
    }

    private func escape(_ text: String) -> String {
        text.replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
            .replacingOccurrences(of: "\"", with: "&quot;")
            .replacingOccurrences(of: "'", with: "&#39;")
    }
}
