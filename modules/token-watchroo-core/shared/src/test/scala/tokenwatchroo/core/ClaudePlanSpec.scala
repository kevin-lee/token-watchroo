package tokenwatchroo.core

import cats.syntax.all.*
import hedgehog.*
import hedgehog.extra.refined4s.gens.NumGens
import hedgehog.runner.*
import refined4s.types.all.*
import tokenwatchroo.core.providers.*

object ClaudePlanSpec extends Properties {

  override def tests: List[Test] = List(
    property("a Max tier round-trips its multiplier", testMaxTierRoundTrip),
    example("bare and malformed tiers", testBareAndMalformedTiers),
    example("organization type wins and the payload follows the plan", testOrganizationType),
    example("account flags are the fallback", testAccountFlags),
    example("credentials prefer the subscription type", testCredentials),
    example("team seats parse and render", testTeamSeat),
    property("every plan label is non-empty and starts with the plan name", testLabelPrefix),
  )

  private def labelOf(plan: Option[ClaudePlan]): Option[String] = plan.map(_.label.value.value)

  def testMaxTierRoundTrip: Property =
    for {
      n <- NumGens.genPosInt(PosInt(1), PosInt(100)).log("n")
    } yield {
      val expected = ClaudePlan.max(UsageMultiplier(n).some)
      Result.all(
        List(
          ClaudePlan.fromRateLimitTier(s"default_claude_max_${n.value}x") ==== Some(expected),
          ClaudePlan.fromRateLimitTier(s"v2_default_claude_max_${n.value}x") ==== Some(expected),
          expected.label ==== PlanLabel(NonEmptyString.unsafeFrom(s"Max ${n.value}x")),
        )
      )
    }

  def testBareAndMalformedTiers: Result =
    Result.all(
      List(
        ClaudePlan.fromRateLimitTier("claude_max") ==== Some(ClaudePlan.max(none[UsageMultiplier])),
        labelOf(ClaudePlan.fromRateLimitTier("claude_max")) ==== Some("Max"),
        ClaudePlan.fromRateLimitTier("default_claude_max_x") ==== Some(ClaudePlan.max(none[UsageMultiplier])),
        ClaudePlan.fromRateLimitTier("default_claude_max_0x") ==== Some(ClaudePlan.max(none[UsageMultiplier])),
        ClaudePlan.fromRateLimitTier("default_claude_max_05x") ==== Some(
          ClaudePlan.max(UsageMultiplier(PosInt(5)).some)
        ),
        ClaudePlan.fromRateLimitTier("default_claude_team_5x") ==== Some(ClaudePlan.team(none[TeamSeat])),
        labelOf(ClaudePlan.fromRateLimitTier("default_claude_team_5x")) ==== Some("Team"),
        ClaudePlan.fromRateLimitTier("claude_pro") ==== Some(ClaudePlan.pro),
        ClaudePlan.fromRateLimitTier("claude_enterprise") ==== Some(ClaudePlan.enterprise),
        ClaudePlan.fromRateLimitTier("default_claude_ai") ==== None,
        ClaudePlan.fromRateLimitTier("") ==== None,
      )
    )

  def testOrganizationType: Result = {
    val team = ClaudePlan.fromOrganizationType("claude_team", "default_claude_max_5x".some, "team_standard".some)
    val max  = ClaudePlan.fromOrganizationType("claude_max", "default_claude_max_20x".some, "team_tier_1".some)
    Result.all(
      List(
        team ==== Some(ClaudePlan.team(TeamSeat.standard.some)),
        labelOf(team) ==== Some("Team Standard"),
        max ==== Some(ClaudePlan.max(UsageMultiplier(PosInt(20)).some)),
        labelOf(max) ==== Some("Max 20x"),
        ClaudePlan.fromOrganizationType("claude_pro", "default_claude_max_5x".some, none[String]) ==== Some(
          ClaudePlan.pro
        ),
        ClaudePlan.fromOrganizationType("claude_team", none[String], "team_tier_2".some) ==== Some(
          ClaudePlan.team(none[TeamSeat])
        ),
        ClaudePlan.fromOrganizationType(" Claude_Enterprise ", none[String], none[String]) ==== Some(
          ClaudePlan.enterprise
        ),
        ClaudePlan.fromOrganizationType("enterprise", none[String], none[String]) ==== None,
        ClaudePlan.fromOrganizationType("claude_future", none[String], none[String]) ==== None,
      )
    )
  }

  def testAccountFlags: Result =
    Result.all(
      List(
        ClaudePlan.fromAccount(ClaudeProfileAccount(true.some, true.some), "default_claude_max_5x".some) ==== Some(
          ClaudePlan.max(UsageMultiplier(PosInt(5)).some)
        ),
        ClaudePlan.fromAccount(ClaudeProfileAccount(false.some, true.some), none[String]) ==== Some(ClaudePlan.pro),
        ClaudePlan.fromAccount(ClaudeProfileAccount(false.some, false.some), "default_claude_max_5x".some) ==== None,
        ClaudePlan.fromAccount(ClaudeProfileAccount(none[Boolean], none[Boolean]), none[String]) ==== None,
      )
    )

  private def oauth(subscriptionType: Option[String], rateLimitTier: Option[String]): ClaudeAiOauth =
    ClaudeAiOauth(none[String], none[Long], none[List[String]], subscriptionType, rateLimitTier)

  def testCredentials: Result =
    Result.all(
      List(
        oauth("team".some, "default_claude_max_5x".some).toPlan ==== Some(ClaudePlan.team(none[TeamSeat])),
        oauth("max".some, "default_claude_max_5x".some).toPlan ==== Some(
          ClaudePlan.max(UsageMultiplier(PosInt(5)).some)
        ),
        oauth(none[String], "default_claude_max_20x".some).toPlan ==== Some(
          ClaudePlan.max(UsageMultiplier(PosInt(20)).some)
        ),
        oauth(" MAX ".some, none[String]).toPlan ==== Some(ClaudePlan.max(none[UsageMultiplier])),
        oauth("free".some, "default_claude_max_5x".some).toPlan ==== None,
        oauth("free".some, none[String]).toPlan ==== None,
        oauth(none[String], none[String]).toPlan ==== None,
      )
    )

  def testTeamSeat: Result =
    Result.all(
      List(
        TeamSeat.parse("team_standard") ==== Right(TeamSeat.standard),
        TeamSeat.parse("team_tier_1") ==== Right(TeamSeat.premium),
        TeamSeat.parse("team_premium").isLeft ==== true,
        List(TeamSeat.standard, TeamSeat.premium).map(s => TeamSeat.parse(s.wire)) ==== List(
          Right(TeamSeat.standard),
          Right(TeamSeat.premium),
        ),
      )
    )

  def testLabelPrefix: Property =
    for {
      plan <- Fixtures.genClaudePlan.log("plan")
    } yield {
      val text   = plan.label.value.value
      val prefix = plan match {
        case ClaudePlan.Max(_) => "Max"
        case ClaudePlan.Pro => "Pro"
        case ClaudePlan.Team(_) => "Team"
        case ClaudePlan.Enterprise => "Enterprise"
      }
      Result.all(List(text.nonEmpty ==== true, text.startsWith(prefix) ==== true))
    }
}
