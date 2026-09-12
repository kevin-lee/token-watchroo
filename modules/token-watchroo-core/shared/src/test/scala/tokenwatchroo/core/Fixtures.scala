package tokenwatchroo.core

import cats.syntax.all.*
import hedgehog.*
import hedgehog.extra.refined4s.gens.{NumGens, StringGens}
import refined4s.types.all.*
import tokenwatchroo.core.providers.*

/** Generators and builders shared by the core specs. */
object Fixtures {

  /** 1970 to 2100 */
  val genEpoch: Gen[EpochSeconds] =
    NumGens.genNonNegLong(NonNegLong(0L), NonNegLong(4102444800L)).map(n => EpochSeconds(n.value))

  val genPercent: Gen[UsedPercent] =
    NumGens.genNonNegDouble(NonNegDouble(0.0d), NonNegDouble(100.0d)).map(d => UsedPercent.clamp(d.value))

  /** Names may contain spaces and dots anywhere, so the wire round trip is exercised on the verbatim rule. */
  val genModelName: Gen[ModelName] =
    StringGens
      .genNonEmptyString(Gen.choice1(Gen.alphaNum, Gen.constant(' '), Gen.constant('.')), PosInt(16))
      .map(ModelName(_))

  /** A name that survives `ModelName.fromDisplayName` unchanged, for the sorting property. */
  val genPlainModelName: Gen[ModelName] =
    StringGens.genNonEmptyString(Gen.alphaNum, PosInt(12)).map(ModelName(_))

  val genWindowId: Gen[WindowId] =
    Gen.choice1(Gen.constant(WindowId.session), Gen.constant(WindowId.weekly), genModelName.map(WindowId.model))
  val genAgentId: Gen[AgentId]   = Gen.element1(AgentId.ClaudeCode, AgentId.Codex)

  def window(id: WindowId, percent: Double, resetsAt: Option[EpochSeconds]): UsageWindow =
    UsageWindow.clamped(id, percent, resetsAt, Seconds(18000L).some)

  def available(id: AgentId, now: EpochSeconds, windows: UsageWindow*): AgentSnapshot =
    AgentSnapshot.available(id, none[PlanLabel], windows.toList, Source.Api, now, none[ErrorMessage])

  def unavailable(id: AgentId, now: EpochSeconds): AgentSnapshot =
    AgentSnapshot.unavailable(id, now, ErrorMessage(NonEmptyString("down")))

  def snapshot(now: EpochSeconds, agents: AgentSnapshot*): Snapshot = Snapshot.of(now, agents.toList)

  val genUsageWindow: Gen[UsageWindow] =
    for {
      id       <- genWindowId
      percent  <- genPercent
      resetsAt <- Gen.frequency1(3 -> genEpoch.map(_.some), 1 -> Gen.constant(none[EpochSeconds]))
    } yield UsageWindow(id, percent, resetsAt, Seconds(18000L).some)

  private def genWindowFor(id: WindowId): Gen[UsageWindow] = genUsageWindow.map(_.copy(id = id))

  val genClaudePlan: Gen[ClaudePlan] =
    Gen.choice1(
      NumGens.genPosInt(PosInt(1), PosInt(100)).map(UsageMultiplier(_)).option.map(ClaudePlan.max),
      Gen.constant(ClaudePlan.pro),
      Gen.element1(TeamSeat.Standard, TeamSeat.Premium).option.map(ClaudePlan.team),
      Gen.constant(ClaudePlan.enterprise),
    )

  /** An agent has at most one window per id, so model names are distinct. */
  val genAgentWindows: Gen[List[UsageWindow]] =
    for {
      session <- genWindowFor(WindowId.Session).option
      weekly  <- genWindowFor(WindowId.Weekly).option
      names   <- genModelName.list(Range.linear(0, 3))
      models  <- genUsageWindow.list(Range.linear(0, 3))
    } yield session.toList ++ weekly.toList ++
      names.distinct.zip(models).map { case (name, w) => w.copy(id = WindowId.model(name)) }
}
