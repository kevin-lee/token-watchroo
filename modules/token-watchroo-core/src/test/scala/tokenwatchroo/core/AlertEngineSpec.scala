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
    example("a spend-only agent at 96% gets one spend alert and no window records", testSpendOnly),
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
    property("spend: applying the same snapshot twice emits nothing the second time", testSpendIdempotent),
    property("spend: a level change never fires by itself", testSpendLevelChangeAlone),
    example("spend: a crossing fires once", testSpendCrossingOnce),
    example("spend: several crossings fire only the highest", testSpendSeveral),
    example("spend: a limit change moves the default levels", testSpendLimitChange),
    example("spend: a lower limit fires at once with no reset alert", testSpendLowerLimit),
    example("spend: a meter without a reset fires once per level", testSpendNoReset),
    example("spend: a meter without a reset starts again after a drop", testSpendNoResetStartsAgain),
    example("spend: a monthly meter starts again on a new reset time", testSpendNewMonth),
    example("spend: an expired reset time is not observed and keeps the record", testSpendExpired),
    example("spend: custom amounts compare with the exact spent amount", testSpendExact),
    example("spend: amounts in another unit use the defaults", testSpendUnitMismatch),
    example("spend: no amounts give no alerts and no reset alert", testSpendNoLevels),
    example("spend: the copy and the identifiers follow the plan", testSpendCopy),
    example("spend: an agent with windows and a spend gets both alerts, windows first", testWindowsAndSpend),
    example("spend: records not observed for more than 8 days are pruned", testSpendPrune),
  )

  private def step(
    state: AlertState,
    snapshot: Snapshot,
    at: EpochSeconds,
    levels: AlertLevels = AlertLevels.defaults,
  ): (AlertState, List[Alert]) =
    AlertEngine.step(state, snapshot, at, _ => levels, _ => SpendAlertLevels.defaults)

  /** The kind of a window alert. A spend alert fails the test. */
  private def kindOf(alert: Alert): AlertKind =
    Fixtures.windowKind(alert).fold(sys.error(s"not a window alert: $alert"))(_._2)

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
        first.map(kindOf) ==== List(level85),
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
    Result.all(
      List(
        alerts.map(Fixtures.spendKind) ==== List(SpendAlertKind.percent(AlertLevel.unsafeFrom(85)).some),
        state.records ==== Map.empty[WindowKey, WindowRecord],
        state.spends.keySet ==== Set(AgentId.Codex),
      )
    )
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
      val counts      = alerts.groupBy(kindOf).view.mapValues(_.size).toMap
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
        alerts.map(kindOf) ==== List(level85),
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
        at70.map(kindOf) ==== List(level70),
        at84 ==== Nil,
        at85.map(kindOf) ==== List(level85),
      )
    )
  }

  def testWarningThenCritical: Result = {
    val (state1, alerts1) = step(AlertState.empty, claude(82.0d, resetsAt, now), now)
    val (_, alerts2)      = step(state1, claude(97.0d, resetsAt, now), now)
    Result.all(
      List(
        alerts1.map(kindOf) ==== List(level70),
        alerts1.map(_.title) ==== List("Claude Code is near its session limit"),
        alerts2.map(kindOf) ==== List(level85),
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
        resets.map(kindOf) ==== List(AlertKind.Reset),
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
    Result.all(List(small ==== Nil, large.map(kindOf) ==== List(AlertKind.Reset)))
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
        warnings.map(kindOf) ==== List(level70),
        warnings.map(_.title) ==== List("Claude Code is near its weekly Fable limit"),
        warnings.map(_.body) ==== List("82% of the weekly Fable window used. Resets in 1 hour 12 minutes."),
        warnings.map(_.identifier) ==== List("claude-code.weekly-model:Fable.1789189920.threshold80"),
        crits.map(kindOf) ==== List(level85),
        crits.map(_.title) ==== List("Claude Code is almost out"),
        crits.map(_.body) ==== List("96% of the weekly Fable window used. Resets in 1 hour 12 minutes."),
        resets.map(kindOf) ==== List(AlertKind.Reset),
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
    Result.all(List(at72.map(kindOf) ==== List(level70), at60 ==== Nil, at75 ==== Nil))
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
        at81.map(kindOf) ==== List(level(80)),
        at81.map(_.title) ==== List("Claude Code is near its session limit"),
      )
    )
  }

  def testNewWindowFromZero: Result = {
    val later       = now.plus(Seconds(18000L))
    val nextReset   = resetsAt.plus(Seconds(18000L))
    val (warned, _) = step(AlertState.empty, claude(85.0d, resetsAt, now), now)
    val (_, alerts) = step(warned, claude(75.0d, nextReset, later), later)
    alerts.map(kindOf) ==== List(AlertKind.Reset, level70)
  }

  def testSeveralLevels: Result = {
    val five           = levels(50, 60, 70, 80, 90)
    val (state1, at85) = step(AlertState.empty, claude(85.0d, resetsAt, now), now, five)
    val (_, at91)      = step(state1, claude(91.0d, resetsAt, now), now, five)
    val record         = state1.records.get(WindowKey(AgentId.ClaudeCode, WindowId.Session))
    Result.all(
      List(
        at85.map(kindOf) ==== List(level(80)),
        record.map(_.fired) ==== Some(Set[AlertKind](level(50), level(60), level70, level(80))),
        at91.map(kindOf) ==== List(level(90)),
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
        at86.map(kindOf) ==== List(level85),
      )
    )
  }

  def testTitleFollowsTheBand: Result = {
    val sixty     = levels(60)
    val (_, at90) = step(AlertState.empty, claude(90.0d, resetsAt, now), now, sixty)
    val (_, at65) = step(AlertState.empty, claude(65.0d, resetsAt, now), now, sixty)
    Result.all(
      List(
        at90.map(kindOf) ==== List(level(60)),
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
      AlertEngine.step(
        AlertState.empty,
        snapshot,
        now,
        id => if (id === AgentId.Codex) fifty else AlertLevels.defaults,
        _ => SpendAlertLevels.defaults,
      )
    Result.all(List(alerts.map(_.agent) ==== List(AgentId.Codex), alerts.map(kindOf) ==== List(level(50))))
  }

  /* Spend alerts (#105). */

  private val monthEnd: EpochSeconds = EpochSeconds(now.value + 19L * 86400L)

  private def stepSpend(
    state: AlertState,
    snapshot: Snapshot,
    at: EpochSeconds,
    levels: SpendAlertLevels = SpendAlertLevels.defaults,
  ): (AlertState, List[Alert]) =
    AlertEngine.step(state, snapshot, at, _ => AlertLevels.defaults, _ => levels)

  private def spendSnapshot(agent: AgentId, spend: Spend, at: EpochSeconds = now): Snapshot =
    Fixtures.snapshot(at, Fixtures.withSpend(agent, at, spend))

  private def usdSpend(spent: String, limit: String, reset: Option[EpochSeconds] = monthEnd.some): Spend =
    Fixtures.spend(Fixtures.usd, spent, limit, reset)

  private def creditSpend(spent: String, limit: String = "10000"): Spend =
    Fixtures.spend(Currency.credits, spent, limit, none[EpochSeconds])

  private def spendLevels(unit: Currency, values: Long*): SpendAlertLevels =
    SpendAlertLevels.from(unit, values.toList).fold(reason => sys.error(reason), identity)

  private def pct(value: Int): Option[SpendAlertKind] =
    SpendAlertKind.percent(AlertLevel.unsafeFrom(value)).some

  private def amt(value: Long): Option[SpendAlertKind] =
    SpendAlertKind.amount(SpendLevel.unsafeFrom(value)).some

  private val spendReset: Option[SpendAlertKind] = SpendAlertKind.reset.some

  /** Steps the spends of one agent in order and gives the spend kinds of each step. */
  private def runSpends(
    agent: AgentId,
    levels: SpendAlertLevels,
    spends: Spend*
  ): (AlertState, List[List[Option[SpendAlertKind]]]) =
    spends.foldLeft((AlertState.empty, List.empty[List[Option[SpendAlertKind]]])) {
      case ((state, acc), spend) =>
        val (next, alerts) = stepSpend(state, spendSnapshot(agent, spend), now, levels)
        (next, acc :+ alerts.map(Fixtures.spendKind))
    }

  def testSpendIdempotent: Property =
    for {
      spend  <- Fixtures.genSpend.log("spend")
      levels <- Fixtures.genSpendAlertLevels.log("levels")
    } yield {
      val snapshot          = spendSnapshot(AgentId.Codex, spend)
      val (state1, alerts1) = stepSpend(AlertState.empty, snapshot, now, levels)
      val (state2, alerts2) = stepSpend(state1, snapshot, now, levels)
      Result.all(List(alerts2 ==== Nil, state2 ==== state1)).log(s"first alerts: $alerts1")
    }

  def testSpendLevelChangeAlone: Property =
    for {
      spend  <- Fixtures.genSpend.log("spend")
      before <- Fixtures.genSpendAlertLevels.log("before")
      after  <- Fixtures.genSpendAlertLevels.log("after")
    } yield {
      val snapshot   = spendSnapshot(AgentId.Codex, spend)
      val (state, _) = stepSpend(AlertState.empty, snapshot, now, before)
      val (_, again) = stepSpend(state, snapshot, now, after)
      again ==== Nil
    }

  def testSpendCrossingOnce: Result = {
    val (_, kinds) = runSpends(
      AgentId.ClaudeCode,
      SpendAlertLevels.defaults,
      usdSpend("100", "200"),
      usdSpend("145", "200"),
      usdSpend("146", "200")
    )
    kinds ==== List(Nil, List(pct(70)), Nil)
  }

  def testSpendSeveral: Result = {
    val (state, defaults) = runSpends(AgentId.ClaudeCode, SpendAlertLevels.defaults, usdSpend("180", "200"))
    val (_, custom)       =
      runSpends(AgentId.ClaudeCode, spendLevels(Fixtures.usd, 50L, 100L, 150L), usdSpend("160", "200"))
    Result.all(
      List(
        defaults ==== List(List(pct(85))),
        state.spends.get(AgentId.ClaudeCode).map(_.fired) ==== Set(pct(70), pct(85)).flatten.some,
        custom ==== List(List(amt(150L))),
      )
    )
  }

  def testSpendLimitChange: Result = {
    val (_, kinds) = runSpends(
      AgentId.ClaudeCode,
      SpendAlertLevels.defaults,
      usdSpend("150", "200"),
      usdSpend("150", "300"),
      usdSpend("210", "300"),
    )
    kinds ==== List(List(pct(70)), Nil, List(pct(70)))
  }

  def testSpendLowerLimit: Result = {
    val (_, kinds) =
      runSpends(AgentId.ClaudeCode, SpendAlertLevels.defaults, usdSpend("180", "300"), usdSpend("180", "200"))
    kinds ==== List(Nil, List(pct(85)))
  }

  def testSpendNoReset: Result = {
    val (_, kinds) = runSpends(
      AgentId.Codex,
      SpendAlertLevels.defaults,
      creditSpend("7500"),
      creditSpend("7600"),
      creditSpend("7500"),
      creditSpend("8600"),
    )
    kinds ==== List(List(pct(70)), Nil, Nil, List(pct(85)))
  }

  def testSpendNoResetStartsAgain: Result = {
    val (state1, _)      = stepSpend(AlertState.empty, spendSnapshot(AgentId.Codex, creditSpend("8600")), now)
    val (state2, resets) = stepSpend(state1, spendSnapshot(AgentId.Codex, creditSpend("100")), now)
    val (_, again)       = stepSpend(state2, spendSnapshot(AgentId.Codex, creditSpend("7000")), now)
    Result.all(
      List(
        resets.map(Fixtures.spendKind) ==== List(spendReset),
        resets.map(_.title) ==== List("Codex credits reset"),
        resets.map(_.body) ==== List("The credit limit is available again."),
        again.map(Fixtures.spendKind) ==== List(pct(70)),
      )
    )
  }

  def testSpendNewMonth: Result = {
    val nextMonth        = EpochSeconds(monthEnd.value + 30L * 86400L)
    val (state1, first)  = stepSpend(AlertState.empty, spendSnapshot(AgentId.ClaudeCode, usdSpend("150", "200")), now)
    val (state2, resets) =
      stepSpend(state1, spendSnapshot(AgentId.ClaudeCode, usdSpend("150", "200", nextMonth.some)), now)
    val (_, again)       =
      stepSpend(state2, spendSnapshot(AgentId.ClaudeCode, usdSpend("175", "200", nextMonth.some)), now)
    Result.all(
      List(
        first.map(Fixtures.spendKind) ==== List(pct(70)),
        resets.map(Fixtures.spendKind) ==== List(spendReset, pct(70)),
        resets.map(_.title).headOption ==== "Claude Code budget reset".some,
        resets.map(_.body).headOption ==== "A fresh monthly budget is available.".some,
        again.map(Fixtures.spendKind) ==== List(pct(85)),
      )
    )
  }

  def testSpendExpired: Result = {
    val soon            = EpochSeconds(now.value + 3600L)
    val later           = EpochSeconds(soon.value + 60L)
    val (state1, _)     =
      stepSpend(AlertState.empty, spendSnapshot(AgentId.ClaudeCode, usdSpend("150", "200", soon.some)), now)
    val (state2, after) =
      stepSpend(state1, spendSnapshot(AgentId.ClaudeCode, usdSpend("190", "200", soon.some), later), later)
    Result.all(List(after ==== Nil, state2.spends ==== state1.spends))
  }

  def testSpendExact: Result = {
    val (_, kinds) =
      runSpends(
        AgentId.ClaudeCode,
        spendLevels(Fixtures.usd, 140L),
        usdSpend("139.99", "200"),
        usdSpend("140.00", "200")
      )
    kinds ==== List(Nil, List(amt(140L)))
  }

  def testSpendUnitMismatch: Result = {
    val eur        = Currency.iso(CurrencyCode.unsafeFrom("EUR"))
    val (_, kinds) = runSpends(AgentId.ClaudeCode, spendLevels(eur, 150L), usdSpend("145", "200"))
    kinds ==== List(List(pct(70)))
  }

  def testSpendNoLevels: Result = {
    val noLevels     = spendLevels(Fixtures.usd)
    val (_, nothing) = runSpends(AgentId.ClaudeCode, noLevels, usdSpend("180", "200"))
    val (state1, _)  = stepSpend(AlertState.empty, spendSnapshot(AgentId.ClaudeCode, usdSpend("180", "200")), now)
    val (_, noReset) = stepSpend(state1, spendSnapshot(AgentId.ClaudeCode, usdSpend("10", "200")), now, noLevels)
    Result.all(List(nothing ==== List(Nil), noReset ==== Nil))
  }

  def testSpendCopy: Result = {
    val (_, amber)  = stepSpend(AlertState.empty, spendSnapshot(AgentId.ClaudeCode, usdSpend("145", "200")), now)
    val (_, red)    = stepSpend(AlertState.empty, spendSnapshot(AgentId.ClaudeCode, usdSpend("180", "200")), now)
    val (_, credit) = stepSpend(AlertState.empty, spendSnapshot(AgentId.Codex, creditSpend("7500")), now)
    val (_, out)    = stepSpend(AlertState.empty, spendSnapshot(AgentId.Codex, creditSpend("8600")), now)
    Result.all(
      List(
        amber.map(_.title) ==== List("Claude Code is near its monthly budget"),
        amber.map(_.body) ==== List("$145.00 of $200.00 spent, 73%. Resets in 19 days."),
        amber.map(_.identifier) ==== List(s"claude-code.spend.${monthEnd.value}.spend-pct70"),
        red.map(_.title) ==== List("Claude Code is almost out of its monthly budget"),
        red.map(_.body) ==== List("$180.00 of $200.00 spent, 90%. Resets in 19 days."),
        credit.map(_.title) ==== List("Codex is near its credit limit"),
        credit.map(_.body) ==== List("7,500 of 10,000 credits spent, 75%."),
        out.map(_.title) ==== List("Codex is almost out of its credits"),
        out.map(_.body) ==== List("8,600 of 10,000 credits spent, 86%."),
        out.map(_.identifier) ==== List("codex.spend.none.spend-pct85"),
      )
    )
  }

  def testWindowsAndSpend: Result = {
    val snapshot    = Fixtures.snapshot(
      now,
      Fixtures.withSpend(
        AgentId.ClaudeCode,
        now,
        usdSpend("145", "200"),
        Fixtures.window(WindowId.model(ModelName(NonEmptyString("Fable"))), 75.0d, resetsAt.some),
      ),
    )
    val (_, alerts) = stepSpend(AlertState.empty, snapshot, now)
    alerts.map(_.subject.kindWire) ==== List("threshold80", "spend-pct70")
  }

  def testSpendPrune: Result = {
    val (state, _) = stepSpend(AlertState.empty, spendSnapshot(AgentId.Codex, creditSpend("100")), now)
    val (seven, _) = stepSpend(state, Fixtures.snapshot(now), EpochSeconds(now.value + 7L * 86400L))
    val (nine, _)  = stepSpend(state, Fixtures.snapshot(now), EpochSeconds(now.value + 9L * 86400L))
    Result.all(List(seven.spends.keySet ==== Set(AgentId.Codex), nine.spends ==== Map.empty[AgentId, SpendRecord]))
  }
}
