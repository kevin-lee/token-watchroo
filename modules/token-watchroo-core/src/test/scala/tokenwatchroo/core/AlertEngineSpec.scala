package tokenwatchroo.core

import cats.syntax.all.*
import hedgehog.*
import hedgehog.runner.*
import refined4s.types.all.*
import tokenwatchroo.core.codecs.given

object AlertEngineSpec extends Properties {

  override def tests: List[Test] = List(
    property("applying the same snapshot twice emits nothing the second time", testIdempotent),
    property("at most one alert per kind while usage only grows, for any levels", testOnePerKind),
    property("a level change never fires by itself", testLevelChangeAlone),
    example("idle windows never alert", testIdle),
    example("an agent first seen at 96 gets exactly one alert", testCriticalFirstSeen),
    example("82 then 97 gives two alerts in order", testWarningThenCritical),
    example("levels fire at the number", testBandBoundaries),
    example("reset alerts only after a fired threshold", testResetOnlyAfterThreshold),
    example("60 seconds of reset drift is the same window, five hours is a new one", testDrift),
    example("an unavailable snapshot in between does not reset", testUnavailableInBetween),
    example("records older than 8 days are pruned", testPrune),
    example("per-model windows alert with their own copy and identifier", testPerModelCopy),
    example("a spend-only agent emits no alerts and keeps no records", testSpendOnly),
    example("an expired window fires nothing", testExpired),
    example("an agent hidden and shown again in the same window does not fire again", testHiddenThenShown),
    example("a drop and a rise within 20 points in one window fires once", testDropAndRise),
    example("a level added below the usage does not fire, one added above fires when crossed", testLevelAdded),
    example("a new window after a reset starts from 0", testNewWindowFromZero),
    example("one step over several levels fires only the highest and marks the rest", testSeveralLevels),
    example("no levels gives no alerts and no reset", testNoLevels),
    example("an old record with threshold80 does not fire level 70 again", testOldRecord),
    example("the title says almost out in the red band", testTitleFollowsTheBand),
    example("the levels are per agent", testPerAgentLevels),
  )

  private def step(
    state: AlertState,
    snapshot: Snapshot,
    at: EpochSeconds,
    levels: AlertLevels = AlertLevels.defaults,
  ): (AlertState, List[Alert]) =
    AlertEngine.step(state, snapshot, at, _ => levels)

  private def levels(values: Int*): AlertLevels =
    AlertLevels.from(values.toList).fold(reason => sys.error(reason), identity)

  private def level(value: Int): AlertKind = AlertKind.level(AlertLevel.unsafeFrom(value))

  private val level70: AlertKind = level(70)
  private val level85: AlertKind = level(85)

  /** A disabled agent (#8) is left out of the snapshots. Its record stays, because `prune` keeps records that are not
    * observed until 8 days after their reset, so enabling it again in the same window fires nothing twice.
    */
  def testHiddenThenShown: Result = {
    val later           = EpochSeconds(now.value + 600L)
    val hidden          = Fixtures.snapshot(later)
    val (state1, first) = step(AlertState.empty, claude(85.0d, resetsAt, now), now)
    val (state2, gone)  = step(state1, hidden, later)
    val (state3, gone2) = step(state2, hidden, EpochSeconds(later.value + 60L))
    val (_, shownAgain) = step(
      state3,
      claude(85.0d, resetsAt, EpochSeconds(later.value + 120L)),
      EpochSeconds(later.value + 120L)
    )
    Result.all(
      List(
        first.map(_.kind) ==== List(level85),
        gone ==== Nil,
        gone2 ==== Nil,
        state3 ==== state1,
        shownAgain ==== Nil,
      )
    )
  }

  def testSpendOnly: Result = {
    val spend           = Spend(Currency.credits, Fixtures.amount("24000"), Fixtures.amount("25000"), resetsAt.some)
    val snapshot        = Fixtures.snapshot(now, Fixtures.withSpend(AgentId.Codex, now, spend))
    val (state, alerts) = step(AlertState.empty, snapshot, now)
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
    Fixtures.genAgentWindows.log("windows").map { windows =>
      val snapshot          = Fixtures.snapshot(now, Fixtures.available(AgentId.Codex, now, windows*))
      val (state1, alerts1) = step(AlertState.empty, snapshot, now)
      val (state2, alerts2) = step(state1, snapshot, now)
      Result.all(List(alerts2 ==== Nil, state2 ==== state1)).log(s"first alerts: $alerts1")
    }

  def testOnePerKind: Property =
    for {
      levels   <- Fixtures.genAlertLevels.log("levels")
      percents <- Fixtures
                    .genPercent
                    .list(Range.linear(1, 12))
                    .map(_.sorted(using cats.Order[UsedPercent].toOrdering))
                    .log("percents")
    } yield {
      val (_, alerts) = percents.foldLeft((AlertState.empty, List.empty[Alert])) {
        case ((state, acc), p) =>
          val (next, emitted) = step(state, claude(p.value, resetsAt, now), now, levels)
          (next, acc ++ emitted)
      }
      val counts      = alerts.groupBy(_.kind).view.mapValues(_.size).toMap
      Result.assert(counts.values.forall(_ <= 1)).log(s"alerts: $alerts")
    }

  def testLevelChangeAlone: Property =
    for {
      before  <- Fixtures.genAlertLevels.log("before")
      after   <- Fixtures.genAlertLevels.log("after")
      percent <- Fixtures.genPercent.log("percent")
    } yield {
      val snapshot   = claude(percent.value, resetsAt, now)
      val (state, _) = step(AlertState.empty, snapshot, now, before)
      val (_, again) = step(state, snapshot, now, after)
      again ==== Nil
    }

  def testIdle: Result = {
    val snapshot    = Fixtures.snapshot(
      now,
      Fixtures.available(AgentId.ClaudeCode, now, Fixtures.window(WindowId.Session, 99.0d, none[EpochSeconds]))
    )
    val (_, alerts) = step(AlertState.empty, snapshot, now)
    alerts ==== Nil
  }

  def testCriticalFirstSeen: Result = {
    val (state, alerts) = step(AlertState.empty, claude(96.0d, resetsAt, now), now)
    val record          = state.records.get(WindowKey(AgentId.ClaudeCode, WindowId.Session))
    Result.all(
      List(
        alerts.map(_.kind) ==== List(level85),
        alerts.map(_.title) ==== List("Claude Code is almost out"),
        alerts.map(_.body) ==== List("96% of the 5 h window used. Resets in 1 hour 12 minutes."),
        alerts.map(_.usedPercent) ==== List(UsedPercent.clamp(96.0d)),
        record.map(_.fired) ==== Some(Set[AlertKind](level70, level85)),
      )
    )
  }

  def testBandBoundaries: Result = {
    val (state1, at69) = step(AlertState.empty, claude(69.0d, resetsAt, now), now)
    val (state2, at70) = step(state1, claude(70.0d, resetsAt, now), now)
    val (state3, at84) = step(state2, claude(84.0d, resetsAt, now), now)
    val (_, at85)      = step(state3, claude(85.0d, resetsAt, now), now)
    Result.all(
      List(
        at69 ==== Nil,
        at70.map(_.kind) ==== List(level70),
        at84 ==== Nil,
        at85.map(_.kind) ==== List(level85),
      )
    )
  }

  def testWarningThenCritical: Result = {
    val (state1, alerts1) = step(AlertState.empty, claude(82.0d, resetsAt, now), now)
    val (_, alerts2)      = step(state1, claude(97.0d, resetsAt, now), now)
    Result.all(
      List(
        alerts1.map(_.kind) ==== List(level70),
        alerts1.map(_.title) ==== List("Claude Code is near its session limit"),
        alerts2.map(_.kind) ==== List(level85),
      )
    )
  }

  def testResetOnlyAfterThreshold: Result = {
    val later     = now.plus(Seconds(18000L))
    val nextReset = resetsAt.plus(Seconds(18000L))

    val (quiet1, _)           = step(AlertState.empty, claude(50.0d, resetsAt, now), now)
    val (_, quietResetAlerts) = step(quiet1, claude(5.0d, nextReset, later), later)

    val (warned, _)          = step(AlertState.empty, claude(85.0d, resetsAt, now), now)
    val (afterReset, resets) = step(warned, claude(3.0d, nextReset, later), later)
    val (_, again)           = step(afterReset, claude(3.0d, nextReset, later), later)
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
    val (warned, _) = step(AlertState.empty, claude(85.0d, resetsAt, now), now)
    val (_, small)  = step(warned, claude(85.0d, resetsAt.plus(Seconds(60L)), now), now)
    val (_, large)  = step(warned, claude(1.0d, resetsAt.plus(Seconds(18000L)), now), now)
    Result.all(List(small ==== Nil, large.map(_.kind) ==== List(AlertKind.Reset)))
  }

  def testUnavailableInBetween: Result = {
    val (warned, _)       = step(AlertState.empty, claude(85.0d, resetsAt, now), now)
    val unavailable       = Fixtures.snapshot(now, Fixtures.unavailable(AgentId.ClaudeCode, now))
    val (afterDown, down) = step(warned, unavailable, now)
    val (_, back)         = step(afterDown, claude(85.0d, resetsAt, now), now)
    Result.all(List(down ==== Nil, back ==== Nil, afterDown ==== warned))
  }

  private val fable: WindowId = WindowId.model(ModelName(NonEmptyString("Fable")))

  private def claudeModel(percent: Double, reset: EpochSeconds, at: EpochSeconds): Snapshot =
    Fixtures.snapshot(at, Fixtures.available(AgentId.ClaudeCode, at, Fixtures.window(fable, percent, reset.some)))

  def testPerModelCopy: Result = {
    val week               = Seconds(604800L)
    val (warned, warnings) = step(AlertState.empty, claudeModel(82.0d, resetsAt, now), now)
    val (critical, crits)  = step(warned, claudeModel(96.0d, resetsAt, now), now)
    val (_, resets)        =
      step(critical, claudeModel(3.0d, resetsAt.plus(week), now.plus(week)), now.plus(week))
    Result.all(
      List(
        warnings.map(_.kind) ==== List(level70),
        warnings.map(_.title) ==== List("Claude Code is near its weekly Fable limit"),
        warnings.map(_.body) ==== List("82% of the weekly Fable window used. Resets in 1 hour 12 minutes."),
        warnings.map(_.identifier) ==== List("claude-code.weekly-model:Fable.1789189920.threshold80"),
        crits.map(_.kind) ==== List(level85),
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
    val (state, _)  = step(AlertState.empty, claude(85.0d, old, old), old)
    val (pruned, _) = step(state, Fixtures.snapshot(now), now)
    Result.all(List(state.records.size ==== 1, pruned.records.size ==== 0))
  }

  def testExpired: Result = {
    val (_, alerts) = step(AlertState.empty, claude(96.0d, EpochSeconds(now.value - 1L), now), now)
    alerts ==== Nil
  }

  def testDropAndRise: Result = {
    val (state1, at72) = step(AlertState.empty, claude(72.0d, resetsAt, now), now)
    val (state2, at60) = step(state1, claude(60.0d, resetsAt, now), now)
    val (_, at75)      = step(state2, claude(75.0d, resetsAt, now), now)
    Result.all(List(at72.map(_.kind) ==== List(level70), at60 ==== Nil, at75 ==== Nil))
  }

  def testLevelAdded: Result = {
    val (state1, _)    = step(AlertState.empty, claude(75.0d, resetsAt, now), now)
    val below          = levels(60, 70, 85)
    val (state2, at76) = step(state1, claude(76.0d, resetsAt, now), now, below)
    val above          = levels(70, 80, 85)
    val (state3, at79) = step(state2, claude(79.0d, resetsAt, now), now, above)
    val (_, at81)      = step(state3, claude(81.0d, resetsAt, now), now, above)
    Result.all(
      List(
        at76 ==== Nil,
        at79 ==== Nil,
        at81.map(_.kind) ==== List(level(80)),
        at81.map(_.title) ==== List("Claude Code is near its session limit"),
      )
    )
  }

  def testNewWindowFromZero: Result = {
    val later       = now.plus(Seconds(18000L))
    val nextReset   = resetsAt.plus(Seconds(18000L))
    val (warned, _) = step(AlertState.empty, claude(85.0d, resetsAt, now), now)
    val (_, alerts) = step(warned, claude(75.0d, nextReset, later), later)
    alerts.map(_.kind) ==== List(AlertKind.Reset, level70)
  }

  def testSeveralLevels: Result = {
    val five           = levels(50, 60, 70, 80, 90)
    val (state1, at85) = step(AlertState.empty, claude(85.0d, resetsAt, now), now, five)
    val (_, at91)      = step(state1, claude(91.0d, resetsAt, now), now, five)
    val record         = state1.records.get(WindowKey(AgentId.ClaudeCode, WindowId.Session))
    Result.all(
      List(
        at85.map(_.kind) ==== List(level(80)),
        record.map(_.fired) ==== Some(Set[AlertKind](level(50), level(60), level70, level(80))),
        at91.map(_.kind) ==== List(level(90)),
      )
    )
  }

  def testNoLevels: Result = {
    val later           = now.plus(Seconds(18000L))
    val nextReset       = resetsAt.plus(Seconds(18000L))
    val (state1, at99)  = step(AlertState.empty, claude(99.0d, resetsAt, now), now, AlertLevels.none)
    val (_, afterReset) = step(state1, claude(3.0d, nextReset, later), later, AlertLevels.none)
    Result.all(List(at99 ==== Nil, afterReset ==== Nil))
  }

  /** `threshold80` in a `state.json` of 0.2.x is level 70, so the upgrade does not fire it again. */
  def testOldRecord: Result = {
    val json           =
      """{"records":[{"agent":"claude-code","window":"session","resetsAt":1789189920,"lastPercent":75.0,"fired":["threshold80"]}]}"""
    val old            = codecs.readEither[AlertState](json).getOrElse(AlertState.empty)
    val (state1, at76) = step(old, claude(76.0d, resetsAt, now), now)
    val (_, at86)      = step(state1, claude(86.0d, resetsAt, now), now)
    Result.all(
      List(
        old.records.size ==== 1,
        at76 ==== Nil,
        at86.map(_.kind) ==== List(level85),
      )
    )
  }

  def testTitleFollowsTheBand: Result = {
    val sixty     = levels(60)
    val (_, at90) = step(AlertState.empty, claude(90.0d, resetsAt, now), now, sixty)
    val (_, at65) = step(AlertState.empty, claude(65.0d, resetsAt, now), now, sixty)
    Result.all(
      List(
        at90.map(_.kind) ==== List(level(60)),
        at90.map(_.title) ==== List("Claude Code is almost out"),
        at65.map(_.title) ==== List("Claude Code is near its session limit"),
      )
    )
  }

  def testPerAgentLevels: Result = {
    val snapshot    = Fixtures.snapshot(
      now,
      Fixtures.available(AgentId.ClaudeCode, now, Fixtures.window(WindowId.Session, 55.0d, resetsAt.some)),
      Fixtures.available(AgentId.Codex, now, Fixtures.window(WindowId.Session, 55.0d, resetsAt.some)),
    )
    val fifty       = levels(50)
    val (_, alerts) =
      AlertEngine.step(AlertState.empty, snapshot, now, id => if (id === AgentId.Codex) fifty else AlertLevels.defaults)
    Result.all(List(alerts.map(_.agent) ==== List(AgentId.Codex), alerts.map(_.kind) ==== List(level(50))))
  }
}
