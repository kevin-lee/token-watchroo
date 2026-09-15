package tokenwatchroo.core

import cats.syntax.all.*
import hedgehog.*
import hedgehog.runner.*
import refined4s.types.all.*

object MenubarStateSpec extends Properties {

  override def tests: List[Test] = List(
    property("the ring shows the maximum percent across all windows of available agents", testMaximum),
    example("an exhausted window wins and carries the soonest reset", testExhausted),
    example("unavailable agents are ignored", testUnavailableIgnored),
    example("no available agent gives the unavailable state", testNoAgents),
    example("an exhausted per-model window drives the ring", testExhaustedPerModel),
    property("the ring shows the maximum over windows and spends of available agents", testMaximumWithSpends),
    example("an exhausted spend drives the ring and carries its reset", testExhaustedSpend),
  )

  private val now = EpochSeconds(1789185600L)

  def testMaximumWithSpends: Property =
    for {
      claude      <- Fixtures.genAgentWindows.log("claude")
      claudeSpend <- Fixtures.genSpend.option.log("claudeSpend")
      codexSpend  <- Fixtures.genSpend.log("codexSpend")
    } yield {
      val agents   = List(
        AgentSnapshot.available(
          AgentId.ClaudeCode,
          none[PlanLabel],
          UsageMeters(claude, claudeSpend),
          Source.Api,
          now,
          none[ErrorMessage],
        ),
        Fixtures.withSpend(AgentId.Codex, now, codexSpend),
      )
      val percents = claude.map(_.usedPercent) ++ claudeSpend.map(_.usedPercent).toList :+ codexSpend.usedPercent
      MenubarState.derive(agents).usedPercent ==== percents.maxOption(using cats.Order[UsedPercent].toOrdering)
    }

  def testExhaustedSpend: Result = {
    val spend   =
      Spend(Currency.credits, Fixtures.amount("25000"), Fixtures.amount("25000"), EpochSeconds(1790812800L).some)
    val agents  = List(
      Fixtures.available(AgentId.ClaudeCode, now, Fixtures.window(WindowId.Session, 40.0d, now.some)),
      Fixtures.withSpend(AgentId.Codex, now, spend),
    )
    val derived = MenubarState.derive(agents)
    Result.all(
      List(
        derived.kind ==== MenubarKind.Exhausted,
        derived.usedPercent ==== Some(UsedPercent.clamp(100.0d)),
        derived.resetsAt ==== Some(EpochSeconds(1790812800L)),
      )
    )
  }

  def testMaximum: Property =
    for {
      claude <- Fixtures.genAgentWindows.log("claude")
      codex  <- Fixtures.genAgentWindows.log("codex")
    } yield {
      val agents   =
        List(Fixtures.available(AgentId.ClaudeCode, now, claude*), Fixtures.available(AgentId.Codex, now, codex*))
      val expected = (claude ++ codex).map(_.usedPercent).maxOption(using cats.Order[UsedPercent].toOrdering)
      MenubarState.derive(agents).usedPercent ==== expected
    }

  def testExhausted: Result = {
    val agents  = List(
      Fixtures.available(
        AgentId.ClaudeCode,
        now,
        Fixtures.window(WindowId.Session, 100.0d, EpochSeconds(1789190000L).some),
        Fixtures.window(WindowId.Weekly, 40.0d, EpochSeconds(1789500000L).some),
      ),
      Fixtures.available(AgentId.Codex, now, Fixtures.window(WindowId.Session, 100.0d, EpochSeconds(1789187000L).some)),
    )
    val derived = MenubarState.derive(agents)
    Result.all(
      List(
        derived.kind ==== MenubarKind.Exhausted,
        derived.resetsAt ==== Some(EpochSeconds(1789187000L)),
        derived.usedPercent ==== Some(UsedPercent.clamp(100.0d)),
      )
    )
  }

  def testUnavailableIgnored: Result = {
    val agents  = List(
      Fixtures.unavailable(AgentId.ClaudeCode, now),
      Fixtures.available(AgentId.Codex, now, Fixtures.window(WindowId.Session, 82.0d, now.some)),
    )
    val derived = MenubarState.derive(agents)
    Result.all(List(derived.kind ==== MenubarKind.Warning, derived.usedPercent ==== Some(UsedPercent.clamp(82.0d))))
  }

  def testExhaustedPerModel: Result = {
    val fable   = WindowId.model(ModelName(NonEmptyString("Fable")))
    val agents  = List(
      Fixtures.available(
        AgentId.ClaudeCode,
        now,
        Fixtures.window(WindowId.Session, 40.0d, EpochSeconds(1789190000L).some),
        Fixtures.window(WindowId.Weekly, 45.0d, EpochSeconds(1789500000L).some),
        Fixtures.window(fable, 100.0d, EpochSeconds(1789400000L).some),
      )
    )
    val derived = MenubarState.derive(agents)
    Result.all(
      List(
        derived.kind ==== MenubarKind.Exhausted,
        derived.usedPercent ==== Some(UsedPercent.clamp(100.0d)),
        derived.resetsAt ==== Some(EpochSeconds(1789400000L)),
      )
    )
  }

  def testNoAgents: Result =
    Result.all(
      List(
        MenubarState.derive(Nil) ==== MenubarState.unavailable,
        MenubarState.derive(List(Fixtures.unavailable(AgentId.Codex, now))) ==== MenubarState.unavailable,
      )
    )
}
