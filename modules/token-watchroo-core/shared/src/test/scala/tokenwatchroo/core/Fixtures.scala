package tokenwatchroo.core

import cats.syntax.all.*
import hedgehog.*
import hedgehog.extra.refined4s.gens.NumGens
import refined4s.types.all.*
import tokenwatchroo.core.providers.*

/** Generators and builders shared by the core specs. */
object Fixtures {

  /** 1970 to 2100 */
  val genEpoch: Gen[EpochSeconds] =
    NumGens.genNonNegLong(NonNegLong(0L), NonNegLong(4102444800L)).map(n => EpochSeconds(n.value))

  val genPercent: Gen[UsedPercent] =
    NumGens.genNonNegDouble(NonNegDouble(0.0d), NonNegDouble(100.0d)).map(d => UsedPercent.clamp(d.value))

  val genWindowId: Gen[WindowId] = Gen.element1(WindowId.Session, WindowId.Weekly)
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

  /** An agent has at most one window per id. */
  val genAgentWindows: Gen[List[UsageWindow]] =
    for {
      session <- genWindowFor(WindowId.Session).option
      weekly  <- genWindowFor(WindowId.Weekly).option
    } yield session.toList ++ weekly.toList
}
