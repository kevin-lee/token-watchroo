package tokenwatchroo.core

import cats.syntax.all.*
import hedgehog.*
import hedgehog.runner.*
import refined4s.types.all.*

object AlertEngineSpec extends Properties {

  override def tests: List[Test] = List(
    property("applying the same snapshot twice emits nothing the second time", testIdempotent),
    property("at most one alert per kind while usage only grows", testOnePerKind),
    example("idle windows never alert", testIdle),
    example("an agent first seen above 95 gets exactly one alert", testCriticalFirstSeen),
    example("80 then 95 gives two alerts in order", testWarningThenCritical),
    example("reset alerts only after a fired threshold", testResetOnlyAfterThreshold),
    example("60 seconds of reset drift is the same window, five hours is a new one", testDrift),
    example("an unavailable snapshot in between does not reset", testUnavailableInBetween),
    example("records older than 8 days are pruned", testPrune),
    example("per-model windows alert with their own copy and identifier", testPerModelCopy),
    example("a spend-only agent emits no alerts and keeps no records", testSpendOnly),
  )

  def testSpendOnly: Result = {
    val spend           = Spend(Currency.credits, Fixtures.amount("24000"), Fixtures.amount("25000"), resetsAt.some)
    val snapshot        = Fixtures.snapshot(now, Fixtures.withSpend(AgentId.Codex, now, spend))
    val (state, alerts) = AlertEngine.step(AlertState.empty, snapshot, now)
    Result.all(List(alerts ==== Nil, state ==== AlertState.empty))
  }

  private val now      = EpochSeconds(1789185600L)
  private val resetsAt = EpochSeconds(1789189920L)

  private def claude(percent: Double, reset: EpochSeconds, at: EpochSeconds): Snapshot =
    Fixtures.snapshot(
      at,
      Fixtures.available(AgentId.ClaudeCode, at, Fixtures.window(WindowId.Session, percent, reset.some))
    )

  def testIdempotent: Property =
    for {
      windows <- Fixtures.genAgentWindows.log("windows")
    } yield {
      val snapshot          = Fixtures.snapshot(now, Fixtures.available(AgentId.Codex, now, windows*))
      val (state1, alerts1) = AlertEngine.step(AlertState.empty, snapshot, now)
      val (state2, alerts2) = AlertEngine.step(state1, snapshot, now)
      Result.all(List(alerts2 ==== Nil, state2 ==== state1)).log(s"first alerts: $alerts1")
    }

  def testOnePerKind: Property =
    for {
      percents <- Fixtures
                    .genPercent
                    .list(Range.linear(1, 12))
                    .map(_.sorted(using cats.Order[UsedPercent].toOrdering))
                    .log("percents")
    } yield {
      val (_, alerts) = percents.foldLeft((AlertState.empty, List.empty[Alert])) {
        case ((state, acc), p) =>
          val (next, emitted) = AlertEngine.step(state, claude(p.value, resetsAt, now), now)
          (next, acc ++ emitted)
      }
      val counts      = alerts.groupBy(_.kind).view.mapValues(_.size).toMap
      Result.assert(counts.values.forall(_ <= 1)).log(s"alerts: $alerts")
    }

  def testIdle: Result = {
    val snapshot    = Fixtures.snapshot(
      now,
      Fixtures.available(AgentId.ClaudeCode, now, Fixtures.window(WindowId.Session, 99.0d, none[EpochSeconds]))
    )
    val (_, alerts) = AlertEngine.step(AlertState.empty, snapshot, now)
    alerts ==== Nil
  }

  def testCriticalFirstSeen: Result = {
    val (state, alerts) = AlertEngine.step(AlertState.empty, claude(96.0d, resetsAt, now), now)
    val record          = state.records.get(WindowKey(AgentId.ClaudeCode, WindowId.Session))
    Result.all(
      List(
        alerts.map(_.kind) ==== List(AlertKind.Critical95),
        alerts.map(_.title) ==== List("Claude Code is almost out"),
        alerts.map(_.body) ==== List("96% of the 5 h window used. Resets in 1 hour 12 minutes."),
        alerts.map(_.usedPercent) ==== List(UsedPercent.clamp(96.0d)),
        record.map(_.fired) ==== Some(Set[AlertKind](AlertKind.Critical95, AlertKind.Warning80)),
      )
    )
  }

  def testWarningThenCritical: Result = {
    val (state1, alerts1) = AlertEngine.step(AlertState.empty, claude(82.0d, resetsAt, now), now)
    val (_, alerts2)      = AlertEngine.step(state1, claude(97.0d, resetsAt, now), now)
    Result.all(
      List(
        alerts1.map(_.kind) ==== List(AlertKind.Warning80),
        alerts1.map(_.title) ==== List("Claude Code is near its session limit"),
        alerts2.map(_.kind) ==== List(AlertKind.Critical95),
      )
    )
  }

  def testResetOnlyAfterThreshold: Result = {
    val later     = now.plus(Seconds(18000L))
    val nextReset = resetsAt.plus(Seconds(18000L))

    val (quiet1, _)           = AlertEngine.step(AlertState.empty, claude(50.0d, resetsAt, now), now)
    val (_, quietResetAlerts) = AlertEngine.step(quiet1, claude(5.0d, nextReset, later), later)

    val (warned, _)          = AlertEngine.step(AlertState.empty, claude(85.0d, resetsAt, now), now)
    val (afterReset, resets) = AlertEngine.step(warned, claude(3.0d, nextReset, later), later)
    val (_, again)           = AlertEngine.step(afterReset, claude(3.0d, nextReset, later), later)
    Result.all(
      List(
        quietResetAlerts ==== Nil,
        resets.map(_.kind) ==== List(AlertKind.Reset),
        resets.map(_.title) ==== List("Claude Code session reset"),
        resets.map(_.body) ==== List("A fresh 5 h window is available."),
        resets.map(_.usedPercent) ==== List(UsedPercent.clamp(3.0d)),
        again ==== Nil,
      )
    )
  }

  def testDrift: Result = {
    val (warned, _) = AlertEngine.step(AlertState.empty, claude(85.0d, resetsAt, now), now)
    val (_, small)  = AlertEngine.step(warned, claude(86.0d, resetsAt.plus(Seconds(60L)), now), now)
    val (_, large)  = AlertEngine.step(warned, claude(1.0d, resetsAt.plus(Seconds(18000L)), now), now)
    Result.all(List(small ==== Nil, large.map(_.kind) ==== List(AlertKind.Reset)))
  }

  def testUnavailableInBetween: Result = {
    val (warned, _)       = AlertEngine.step(AlertState.empty, claude(85.0d, resetsAt, now), now)
    val unavailable       = Fixtures.snapshot(now, Fixtures.unavailable(AgentId.ClaudeCode, now))
    val (afterDown, down) = AlertEngine.step(warned, unavailable, now)
    val (_, back)         = AlertEngine.step(afterDown, claude(86.0d, resetsAt, now), now)
    Result.all(List(down ==== Nil, back ==== Nil, afterDown ==== warned))
  }

  private val fable: WindowId = WindowId.model(ModelName(NonEmptyString("Fable")))

  private def claudeModel(percent: Double, reset: EpochSeconds, at: EpochSeconds): Snapshot =
    Fixtures.snapshot(at, Fixtures.available(AgentId.ClaudeCode, at, Fixtures.window(fable, percent, reset.some)))

  def testPerModelCopy: Result = {
    val week               = Seconds(604800L)
    val (warned, warnings) = AlertEngine.step(AlertState.empty, claudeModel(82.0d, resetsAt, now), now)
    val (critical, crits)  = AlertEngine.step(warned, claudeModel(96.0d, resetsAt, now), now)
    val (_, resets)        =
      AlertEngine.step(critical, claudeModel(3.0d, resetsAt.plus(week), now.plus(week)), now.plus(week))
    Result.all(
      List(
        warnings.map(_.kind) ==== List(AlertKind.Warning80),
        warnings.map(_.title) ==== List("Claude Code is near its weekly Fable limit"),
        warnings.map(_.body) ==== List("82% of the weekly Fable window used. Resets in 1 hour 12 minutes."),
        warnings.map(_.identifier) ==== List("claude-code.weekly-model:Fable.1789189920.threshold80"),
        crits.map(_.kind) ==== List(AlertKind.Critical95),
        crits.map(_.title) ==== List("Claude Code is almost out"),
        crits.map(_.body) ==== List("96% of the weekly Fable window used. Resets in 1 hour 12 minutes."),
        resets.map(_.kind) ==== List(AlertKind.Reset),
        resets.map(_.title) ==== List("Claude Code weekly Fable reset"),
        resets.map(_.body) ==== List("A fresh weekly Fable window is available."),
      )
    )
  }

  def testPrune: Result = {
    val old         = EpochSeconds(1789185600L - 9L * 86400L)
    val (state, _)  = AlertEngine.step(AlertState.empty, claude(85.0d, old, old), old)
    val (pruned, _) = AlertEngine.step(state, Fixtures.snapshot(now), now)
    Result.all(List(state.records.size ==== 1, pruned.records.size ==== 0))
  }
}
