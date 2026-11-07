import Foundation

// Mirrors of the JSON contract in the design doc, section 7. Wire strings are the Scala `wire` values.

/// The config that Swift owns in `<stateDir>/config.json` and sends to the library (#8). Every key is optional when it
/// is read, so an older file still loads, and an agent entry that this version cannot read is skipped.
struct Config: Codable, Equatable {
    var stateDir: String
    var refreshIntervalSeconds: Int
    var codexHome: String?
    var claudeCodeVersionOverride: String?
    var agents: [AgentConfig]

    static let defaultRefreshIntervalSeconds = 60

    static func defaults(stateDir: String) -> Config {
        Config(stateDir: stateDir, refreshIntervalSeconds: defaultRefreshIntervalSeconds, codexHome: nil,
               claudeCodeVersionOverride: nil, agents: [])
    }
}

extension Config {

    private enum CodingKeys: String, CodingKey {
        case stateDir, refreshIntervalSeconds, codexHome, claudeCodeVersionOverride, agents
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        stateDir = try container.decodeIfPresent(String.self, forKey: .stateDir) ?? ""
        refreshIntervalSeconds = try container.decodeIfPresent(Int.self, forKey: .refreshIntervalSeconds)
            ?? Config.defaultRefreshIntervalSeconds
        codexHome = try container.decodeIfPresent(String.self, forKey: .codexHome)
        claudeCodeVersionOverride = try container.decodeIfPresent(String.self, forKey: .claudeCodeVersionOverride)
        agents = (try container.decodeIfPresent([LenientAgentConfig].self, forKey: .agents) ?? []).compactMap(\.agent)
    }
}

/// How an agent is paid for. Mirrors `Billing` in the core.
enum Billing: String, Codable, CaseIterable {
    case subscription
    case api

    var displayName: String {
        switch self {
        case .subscription: return "Subscription plan"
        case .api: return "API"
        }
    }

    /// The plans the Add-agent sheet offers for this billing, per `AddAgent.dc.html`.
    var planChoices: [PlanKind] {
        switch self {
        case .subscription: return [.individual, .team]
        case .api: return [.enterprise]
        }
    }
}

/// The plan the user says an agent is on. Mirrors `PlanKind` in the core.
enum PlanKind: String, Codable {
    case individual
    case team
    case enterprise

    var displayName: String {
        switch self {
        case .individual: return "Individual"
        case .team: return "Team"
        case .enterprise: return "Enterprise"
        }
    }
}

/// Whether a configured agent is shown. A JSON boolean on the wire.
enum Enabled: Codable, Equatable {
    case on
    case off

    var isOn: Bool { self == .on }

    init(isOn: Bool) { self = isOn ? .on : .off }

    init(from decoder: Decoder) throws {
        self.init(isOn: try decoder.singleValueContainer().decode(Bool.self))
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(isOn)
    }
}

/// One entry of `Config.agents`. Mirrors `AgentConfig` in the core.
struct AgentConfig: Codable, Equatable {
    let id: AgentId
    var billing: Billing
    var plan: PlanKind
    var enabled: Enabled

    static func defaults(_ id: AgentId) -> AgentConfig {
        AgentConfig(id: id, billing: .subscription, plan: .individual, enabled: .on)
    }
}

/// An agent entry read without failing: an unknown `id`, `billing` or `plan` gives no agent, so the entry is skipped
/// as the core does.
private struct LenientAgentConfig: Decodable {
    let id: String?
    let billing: String?
    let plan: String?
    let enabled: Bool?

    var agent: AgentConfig? {
        guard let id = id.flatMap(AgentId.init(rawValue:)) else { return nil }
        let billing = self.billing.map(Billing.init(rawValue:)) ?? .subscription
        let plan = self.plan.map(PlanKind.init(rawValue:)) ?? .individual
        guard let billing, let plan else { return nil }
        return AgentConfig(id: id, billing: billing, plan: plan, enabled: Enabled(isOn: enabled ?? true))
    }

    private enum CodingKeys: String, CodingKey {
        case id, billing, plan, enabled
    }

    /// A value of the wrong type is read as absent, so one odd entry cannot make the whole file unreadable.
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try? container.decodeIfPresent(String.self, forKey: .id)
        billing = try? container.decodeIfPresent(String.self, forKey: .billing)
        plan = try? container.decodeIfPresent(String.self, forKey: .plan)
        enabled = try? container.decodeIfPresent(Bool.self, forKey: .enabled)
    }
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

enum AgentId: String, Codable, CaseIterable {
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

    /// The reset time has passed: the percent is from a window that no longer runs (issue #81).
    func isExpired(now: Date) -> Bool { resetsAt.map { $0 < Int64(now.timeIntervalSince1970) } ?? false }
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
    let observedAt: Int64?

    var renderedWindows: [UsageWindow] { windows.filter { $0.id.isRendered } }
}

extension AgentSnapshot {

    private enum CodingKeys: String, CodingKey {
        case id, planLabel, status, windows, spend, source, fetchedAt, error, observedAt
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
        observedAt = try container.decodeIfPresent(Int64.self, forKey: .observedAt)
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
