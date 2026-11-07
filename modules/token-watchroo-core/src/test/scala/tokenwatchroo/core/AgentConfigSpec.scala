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
    property("a provider label always wins", testProviderBadgeWins),
    example(
      "without a provider label, Team and Enterprise fill the badge and Individual leaves it empty",
      testBadgeWithoutProviderLabel,
    ),
  )

  private val stateDir = StateDir(NonEmptyString("/tmp/tw"))

  private def configWith(agents: AgentConfig*): Config =
    Config.default(stateDir).copy(agents = agents.toList)

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
    val first  = AgentConfig(AgentId.Codex, Billing.Subscription, PlanKind.Team, Enabled.On)
    val second = AgentConfig(AgentId.Codex, Billing.Subscription, PlanKind.Individual, Enabled.Off)
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
