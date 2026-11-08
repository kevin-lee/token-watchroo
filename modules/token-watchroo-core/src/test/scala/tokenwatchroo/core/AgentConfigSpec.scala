package tokenwatchroo.core

import cats.syntax.all.*
import hedgehog.*
import hedgehog.runner.*
import refined4s.types.all.*

object AgentConfigSpec extends Properties {

  override def tests: List[Test] = List(
    property("Billing and PlanKind parse their wire values", testWireRoundTrip),
    example("unknown Billing and PlanKind values are rejected", testUnknownWire),
    property("no entry gives AutoDetect", testNoEntry),
    property("a disabled entry is hidden for any billing and plan", testDisabled),
    property("an enabled API entry is not supported", testApi),
    property("an enabled subscription entry is fetched with its plan", testSubscription),
    example("with two entries for one agent, the first is used", testFirstEntryWins),
    property("ordered puts listed agents first in list order, then the others in AgentId order", testOrdered),
    example("an empty list keeps AgentId order", testOrderedEmpty),
    property("a new order, plan or interval needs no fetch", testNoFetch),
    property("a change of alert levels only needs no fetch", testNoFetchForLevels),
    property("a change of spend alert levels only needs no fetch", testNoFetchForSpendLevels),
    example(
      "enabling, disabling, billing, codexHome and claudeCodeVersionOverride changes need a fetch",
      testFetch,
    ),
    property("a provider label always wins", testProviderBadgeWins),
    example(
      "without a provider label, Team and Enterprise fill the badge and Individual leaves it empty",
      testBadgeWithoutProviderLabel,
    ),
    property("a change of the Claude Code config folder only needs a fetch", testFetchForConfigDir),
    example(
      "Config.claudeConfigDir uses the first claude-code entry and ignores other agents",
      testClaudeConfigDirFirstEntry,
    ),
  )

  private val stateDir = StateDir(NonEmptyString("/tmp/tw"))

  private def configWith(agents: AgentConfig*): Config =
    Config.default(stateDir).copy(agents = agents.toList)

  def testOrdered: Property =
    for {
      agents <- Fixtures.genAgentConfig.list(Range.linear(0, 3)).log("agents")
      ids    <- Fixtures.genAgentId.list(Range.linear(0, 4)).log("ids")
    } yield {
      val listed   = agents.map(_.id).distinct.zipWithIndex.toMap
      val expected = ids.sortBy(id => (listed.getOrElse(id, Int.MaxValue), AgentId.all.indexOf(id)))
      val result   = AgentChoice.ordered(configWith(agents*), ids)(identity)
      Result.all(
        List(
          result.sortBy(AgentId.all.indexOf(_)) ==== ids.sortBy(AgentId.all.indexOf(_)),
          result ==== expected,
        )
      )
    }

  def testNoFetch: Property =
    for {
      all <- Fixtures.genAgentConfig.list(Range.linear(0, 3)).log("all")
      agents = all.distinctBy(_.id)
      plan     <- Fixtures.genPlanKind.log("plan")
      interval <- Gen.int(Range.linear(15, 3600)).log("interval")
    } yield {
      val old  = configWith(agents*)
      val next = old.copy(
        agents = agents.reverse.map(_.copy(plan = plan)),
        refreshInterval = RefreshIntervalSeconds.clamp(interval),
      )
      Result.assert(!AgentChoice.needsFetch(old, next, AgentId.all))
    }

  /** The levels are used by the alert engine at the next tick (#9), so a change does not fetch again. */
  def testNoFetchForLevels: Property =
    for {
      all <- Fixtures.genAgentConfig.list(Range.linear(1, 3)).log("all")
      agents = all.distinctBy(_.id)
      levels <- Fixtures.genAlertLevels.log("levels")
    } yield {
      val old  = configWith(agents*)
      val next = old.copy(agents = agents.map(_.copy(alertLevels = levels)))
      Result.assert(!AgentChoice.needsFetch(old, next, AgentId.all))
    }

  /** The spend levels are used by the alert engine at the next tick (#105), so a change does not fetch again. */
  def testNoFetchForSpendLevels: Property =
    for {
      all <- Fixtures.genAgentConfig.list(Range.linear(1, 3)).log("all")
      agents = all.distinctBy(_.id)
      levels <- Fixtures.genSpendAlertLevels.log("levels")
    } yield {
      val old  = configWith(agents*)
      val next = old.copy(agents = agents.map(_.copy(spendAlertLevels = levels)))
      Result.assert(!AgentChoice.needsFetch(old, next, AgentId.all))
    }

  def testFetch: Result = {
    val codex   = AgentConfig.defaults(AgentId.Codex)
    val base    = configWith(codex)
    val changes = List(
      base.copy(agents = List(codex.copy(enabled = Enabled.Off))),
      configWith(),
      base.copy(agents = List(codex.copy(billing = Billing.Api))),
      base.copy(codexHome = CodexHome(NonEmptyString("/tmp/codex")).some),
      base.copy(claudeCodeVersionOverride = ClaudeCodeVersion.fallback.some),
    )
    Result.all(changes.map(next => Result.assert(AgentChoice.needsFetch(base, next, AgentId.all)).log(next.show)))
  }

  /** Another folder reads another keychain item (#91), so the change fetches at once. */
  def testFetchForConfigDir: Property =
    for {
      old  <- Fixtures.genClaudeConfigDir.option.log("old")
      next <- Fixtures.genClaudeConfigDir.option.log("next")
    } yield {
      val claude = AgentConfig.defaults(AgentId.ClaudeCode)
      AgentChoice.needsFetch(
        configWith(claude.copy(claudeConfigDir = old)),
        configWith(claude.copy(claudeConfigDir = next)),
        AgentId.all,
      ) ==== (old =!= next)
    }

  def testClaudeConfigDirFirstEntry: Result = {
    val work  = ClaudeConfigDir.unsafeFrom("/Users/alex/.claude-work")
    val other = ClaudeConfigDir.unsafeFrom("/Users/alex/.claude-other")
    val codex = AgentConfig.defaults(AgentId.Codex).copy(claudeConfigDir = other.some)
    val first = AgentConfig.defaults(AgentId.ClaudeCode).copy(claudeConfigDir = work.some)
    val later = AgentConfig.defaults(AgentId.ClaudeCode).copy(claudeConfigDir = other.some)
    Result.all(
      List(
        configWith(codex, first, later).claudeConfigDir ==== work.some,
        configWith(codex).claudeConfigDir ==== none[ClaudeConfigDir],
        configWith(AgentConfig.defaults(AgentId.ClaudeCode), later).claudeConfigDir ==== none[ClaudeConfigDir],
      )
    )
  }

  def testOrderedEmpty: Result =
    AgentChoice.ordered(configWith(), List(AgentId.Codex, AgentId.ClaudeCode))(identity) ====
      List(AgentId.ClaudeCode, AgentId.Codex)

  private def label(s: String): PlanLabel = PlanLabel(NonEmptyString.unsafeFrom(s))

  def testWireRoundTrip: Property =
    for {
      billing <- Fixtures.genBilling.log("billing")
      plan    <- Fixtures.genPlanKind.log("plan")
    } yield Result.all(
      List(
        Billing.parse(billing.wire) ==== Right(billing),
        PlanKind.parse(plan.wire) ==== Right(plan),
      )
    )

  def testUnknownWire: Result =
    Result.all(
      List(
        Billing.parse("Subscription").isLeft ==== true,
        Billing.parse("budget").isLeft ==== true,
        PlanKind.parse("family").isLeft ==== true,
        PlanKind.parse(" team").isLeft ==== true,
      )
    )

  def testNoEntry: Property =
    Fixtures.genAgentId.log("id").map { id =>
      val other = AgentId.all.filter(_ =!= id).map(AgentConfig.defaults)
      Result.all(
        List(
          AgentChoice.of(configWith(), id) ==== AgentChoice.autoDetect,
          AgentChoice.of(configWith(other*), id) ==== AgentChoice.autoDetect,
        )
      )
    }

  def testDisabled: Property =
    Fixtures.genAgentConfig.log("agent").map { agent =>
      AgentChoice.of(configWith(agent.copy(enabled = Enabled.Off)), agent.id) ==== AgentChoice.hidden
    }

  def testApi: Property =
    Fixtures.genAgentConfig.log("agent").map { agent =>
      AgentChoice.of(
        configWith(agent.copy(billing = Billing.Api, enabled = Enabled.On)),
        agent.id,
      ) ==== AgentChoice.apiNotSupported
    }

  def testSubscription: Property =
    Fixtures.genAgentConfig.log("agent").map { agent =>
      AgentChoice.of(
        configWith(agent.copy(billing = Billing.Subscription, enabled = Enabled.On)),
        agent.id,
      ) ==== AgentChoice.fetch(agent.plan)
    }

  def testFirstEntryWins: Result = {
    val first  = AgentConfig(
      AgentId.Codex,
      Billing.Subscription,
      PlanKind.Team,
      Enabled.On,
      AlertLevels.defaults,
      SpendAlertLevels.defaults,
      none[ClaudeConfigDir],
    )
    val second = AgentConfig(
      AgentId.Codex,
      Billing.Subscription,
      PlanKind.Individual,
      Enabled.Off,
      AlertLevels.none,
      SpendAlertLevels.defaults,
      none[ClaudeConfigDir],
    )
    AgentChoice.of(configWith(first, second), AgentId.Codex) ==== AgentChoice.fetch(PlanKind.Team)
  }

  def testProviderBadgeWins: Property =
    for {
      plan     <- Fixtures.genPlanKind.log("plan")
      provider <- Gen.element1("Pro", "Max 5x", "Plus", "Team", "Team Premium", "Enterprise").map(label).log("provider")
    } yield plan.badge(provider.some) ==== Some(provider)

  def testBadgeWithoutProviderLabel: Result =
    Result.all(
      List(
        PlanKind.Individual.badge(none[PlanLabel]) ==== None,
        PlanKind.Team.badge(none[PlanLabel]) ==== Some(label("Team")),
        PlanKind.Enterprise.badge(none[PlanLabel]) ==== Some(label("Enterprise")),
      )
    )
}
