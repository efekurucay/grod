import Foundation

public enum WebDecision: Equatable, Sendable {
    case allow
    case redirect(URL)
    case block(ruleID: String)
}

extension Rules {
    public static func host(_ host: String, matches configuredHost: String) -> Bool {
        host == configuredHost || host.hasSuffix("." + configuredHost)
    }

    public func decision(for url: URL) -> WebDecision {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let host = components.host?.lowercased(),
              let scheme = components.scheme, ["http", "https"].contains(scheme) else { return .allow }
        let path = components.percentEncodedPath.isEmpty ? "/" : components.percentEncodedPath
        let target = path + (components.percentEncodedQuery.map { "?" + $0 } ?? "")
        let range = NSRange(target.startIndex..., in: target)
        let matching = rules.filter { rule in
            rule.enabled && (rule.web?.hosts.contains { Self.host(host, matches: $0) } ?? false)
        }
        // docs/rules.md "Web": redirects of every matching rule run before any block.
        for rule in matching {
            for redirect in rule.web?.redirect ?? [] {
                guard let regex = try? NSRegularExpression(pattern: redirect.from),
                      let match = regex.firstMatch(in: target, range: range),
                      let matchedRange = Range(match.range, in: target) else { continue }
                let replacement = regex.replacementString(for: match, in: target, offset: 0, template: redirect.to)
                let newTarget = target.replacingCharacters(in: matchedRange, with: replacement)
                if newTarget != target {
                    var origin = components
                    origin.user = nil
                    origin.password = nil
                    origin.path = ""
                    origin.query = nil
                    origin.fragment = nil
                    if let originString = origin.string, let destination = URL(string: originString + newTarget) {
                        return .redirect(destination)
                    }
                }
            }
        }
        for rule in matching {
            for pattern in rule.web?.block ?? [] {
                if let regex = try? NSRegularExpression(pattern: pattern), regex.firstMatch(in: target, range: range) != nil {
                    return .block(ruleID: rule.id)
                }
            }
        }
        return .allow
    }
}
