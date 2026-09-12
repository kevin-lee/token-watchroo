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

enum WindowId: String, Decodable {
    case session
    case weekly

    var label: String {
        switch self {
        case .session: return "Session"
        case .weekly: return "Weekly"
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
