import Foundation

// Mirrors of the JSON contract in the design doc, section 7. Wire strings are the Scala `wire` values.

struct Config: Encodable {
    let stateDir: String
    let refreshIntervalSeconds: Int
    let codexHome: String?
    let claudeCodeVersionOverride: String?
}

struct EnvelopeHead: Decodable {
    let version: Int
    let type: String
    let seq: Int64
}

struct SnapshotEnvelope: Decodable {
    let data: Snapshot
}

struct AlertEnvelope: Decodable {
    let data: AlertPayload
}

struct ErrorEnvelope: Decodable {
    let message: String
}

enum AgentId: String, Decodable {
    case claudeCode = "claude-code"
    case codex

    var displayName: String {
        switch self {
        case .claudeCode: return "Claude Code"
        case .codex: return "Codex"
        }
    }
}

/// Mirrors `WindowId.parse` in the core: `session`, `weekly`, or `weekly-model:<name>` split at the first colon with a
/// non-empty name. An unknown id is kept as `.unknown` so a newer library never makes the shell drop a snapshot.
enum WindowId: Decodable, Equatable {
    case session
    case weekly
    case model(String)
    case unknown(String)

    private static let modelPrefix = "weekly-model:"

    init(from decoder: Decoder) throws {
        let raw = try decoder.singleValueContainer().decode(String.self)
        self = WindowId.parse(raw)
    }

    static func parse(_ raw: String) -> WindowId {
        if raw == "session" { return .session }
        if raw == "weekly" { return .weekly }
        if raw.hasPrefix(modelPrefix) {
            let name = String(raw.dropFirst(modelPrefix.count))
            return name.isEmpty ? .unknown(raw) : .model(name)
        }
        return .unknown(raw)
    }

    var label: String {
        switch self {
        case .session: return "Session"
        case .weekly: return "Weekly"
        case .model(let name): return "Weekly (\(name))"
        case .unknown(let raw): return raw
        }
    }

    /// False only for an id this shell does not know, which the card never draws.
    var isRendered: Bool {
        switch self {
        case .session, .weekly, .model: return true
        case .unknown: return false
        }
    }
}

enum AgentStatus: String, Decodable {
    case ok, warning, critical, exhausted, unavailable
}

enum Source: String, Decodable {
    case api
    case localLog = "local-log"
}

enum MenubarKind: String, Decodable {
    case normal, warning, critical, exhausted, unavailable
}

struct UsageWindow: Decodable {
    let id: WindowId
    let usedPercent: Double
    let resetsAt: Int64?
    let windowLength: Int64?

    var isIdle: Bool { resetsAt == nil }
}

struct AgentSnapshot: Decodable {
    let id: AgentId
    let planLabel: String?
    let status: AgentStatus
    let windows: [UsageWindow]
    let source: Source?
    let fetchedAt: Int64
    let error: String?

    var renderedWindows: [UsageWindow] { windows.filter { $0.id.isRendered } }
}

struct MenubarState: Decodable {
    let kind: MenubarKind
    let usedPercent: Double?
    let resetsAt: Int64?
}

struct Snapshot: Decodable {
    let updatedAt: Int64
    let agents: [AgentSnapshot]
    let menubar: MenubarState
}

struct AlertPayload: Decodable {
    let agent: AgentId
    let window: WindowId
    let kind: String
    let windowResetsAt: Int64
    let title: String
    let body: String
    let identifier: String
}
