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

/// Mirrors `Spend` in the core (issue #33). Amounts are decimal strings on the wire so they never pass through a
/// `Double`. `currency` is an ISO 4217 code or `credits`. `usedPercent` is computed by the core.
struct Spend: Decodable {
    let currency: String
    let spent: Decimal
    let limit: Decimal
    let usedPercent: Double
    let resetsAt: Int64?

    var isCredits: Bool { currency == "credits" }
}

extension Spend {

    private enum CodingKeys: String, CodingKey {
        case currency, spent, limit, usedPercent, resetsAt
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        currency = try container.decode(String.self, forKey: .currency)
        spent = try Spend.decimal(in: container, forKey: .spent)
        limit = try Spend.decimal(in: container, forKey: .limit)
        usedPercent = try container.decode(Double.self, forKey: .usedPercent)
        resetsAt = try container.decodeIfPresent(Int64.self, forKey: .resetsAt)
    }

    private static func decimal(in container: KeyedDecodingContainer<CodingKeys>, forKey key: CodingKeys) throws -> Decimal {
        let text = try container.decode(String.self, forKey: key)
        guard let value = Decimal(string: text, locale: Locale(identifier: "en_US_POSIX")) else {
            throw DecodingError.dataCorruptedError(forKey: key, in: container, debugDescription: "not a decimal amount: \(text)")
        }
        return value
    }
}

struct AgentSnapshot: Decodable {
    let id: AgentId
    let planLabel: String?
    let status: AgentStatus
    let windows: [UsageWindow]
    let spend: Spend?
    let source: Source?
    let fetchedAt: Int64
    let error: String?

    var renderedWindows: [UsageWindow] { windows.filter { $0.id.isRendered } }
}

extension AgentSnapshot {

    private enum CodingKeys: String, CodingKey {
        case id, planLabel, status, windows, spend, source, fetchedAt, error
    }

    /// A missing `windows` decodes as no windows, as for `Snapshot.agents`. A missing `spend` is no spend.
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(AgentId.self, forKey: .id)
        planLabel = try container.decodeIfPresent(String.self, forKey: .planLabel)
        status = try container.decode(AgentStatus.self, forKey: .status)
        windows = try container.decodeIfPresent([UsageWindow].self, forKey: .windows) ?? []
        spend = try container.decodeIfPresent(Spend.self, forKey: .spend)
        source = try container.decodeIfPresent(Source.self, forKey: .source)
        fetchedAt = try container.decode(Int64.self, forKey: .fetchedAt)
        error = try container.decodeIfPresent(String.self, forKey: .error)
    }
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

extension Snapshot {

    private enum CodingKeys: String, CodingKey {
        case updatedAt, agents, menubar
    }

    /// A missing `agents` decodes as no agents, because libraries before issue #32 left an empty list out.
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        updatedAt = try container.decode(Int64.self, forKey: .updatedAt)
        agents = try container.decodeIfPresent([AgentSnapshot].self, forKey: .agents) ?? []
        menubar = try container.decode(MenubarState.self, forKey: .menubar)
    }
}

struct AlertPayload: Decodable {
    let agent: AgentId
    let window: WindowId
    let kind: String
    let windowResetsAt: Int64
    /// Optional so an older library still decodes.
    let usedPercent: Double?
    let title: String
    let body: String
    let identifier: String
}
