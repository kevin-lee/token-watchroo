package tokenwatchroo.core

import cats.syntax.all.*
import hedgehog.*
import hedgehog.runner.*

object MenubarStateSpec extends Properties {

  override def tests: List[Test] = List(
    property("the ring shows the maximum percent across all windows of available agents", testMaximum),
    example("an exhausted window wins and carries the soonest reset", testExhausted),
    example("unavailable agents are ignored", testUnavailableIgnored),
    example("no available agent gives the unavailable state", testNoAgents),
  )

  private val now = EpochSeconds(1789185600L)

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

  def testNoAgents: Result =
    Result.all(
      List(
        MenubarState.derive(Nil) ==== MenubarState.unavailable,
        MenubarState.derive(List(Fixtures.unavailable(AgentId.Codex, now))) ==== MenubarState.unavailable,
      )
    )
}
