package tokenwatchroo.core

import cats.Order
import cats.syntax.all.*

/** Decides which notifications to fire for a snapshot. Pure, deterministic, and idempotent: applying the same snapshot
  * twice emits nothing the second time. Windows that expired at `now` are not observed, so stale values never fire
  * (issue #81). Their records stay until `prune` drops them, so the reset alert still fires when the next window
  * arrives.
  *
  * Alarm-clock rule (#9): a level of the agent fires when the shown whole percent crosses it, from the last shown
  * percent of the same window (0 for a window with no record or a new window) to the shown percent now. Only the highest
  * crossed level fires, and all crossed levels are marked as fired, so each level fires at most once per window. A
  * level change never fires by itself.
  *
  * A new window starts from 0, with a reset alert when a level fired in the old window, at every reset (#110). In the
  * same window the record keeps the later reset time, so a reset time that moves back and forth by more than 120 s
  * does not start new windows.
  *
  * Spends (#105) follow the same rule with `SpendAlertLevels`, from the last spent amount of the same meter. A meter
  * is new when its reset time moved forward by more than 120 s or its percent dropped by more than 20 points, with a
  * reset alert when a level fired in the old meter, at every new meter (#110). In the same meter the record keeps the
  * later reset time. A limit or unit change starts a new record with no reset alert. A spend whose reset time has
  * passed is not observed.
  *
  * The notification copy is in the language of `messages` (#10).
  */
object AlertEngine {

  /** Reset-time drift tolerated before a window counts as new. */
  val ResetDriftTolerance: Seconds = Seconds(120L)

  /** Usage drop tolerated before a window counts as new. */
  val UsageDropTolerance: Double = 20.0d

  def step(
    state: AlertState,
    snapshot: Snapshot,
    now: EpochSeconds,
    levelsOf: AgentId => AlertLevels,
    spendLevelsOf: AgentId => SpendAlertLevels,
    messages: Messages,
  ): (AlertState, List[Alert]) = {
    val available    = snapshot.agents.filter(_.isAvailable)
    val observations =
      for {
        agent    <- available
        window   <- agent.windows.filterNot(_.isExpiredAt(now))
        resetsAt <- window.resetsAt.toList
      } yield (agent.id, window, resetsAt)
    val spends       =
      for {
        agent <- available
        spend <- agent.spend.filterNot(_.isExpiredAt(now)).toList
      } yield (agent.id, spend)

    val (windowState, windowAlerts) = observations.foldLeft((state, List.empty[Alert])) {
      case ((accState, accAlerts), (agentId, window, resetsAt)) =>
        val (updatedState, newAlerts) =
          stepWindow(accState, agentId, levelsOf(agentId), window, resetsAt, now, messages)
        (updatedState, accAlerts ++ newAlerts)
    }
    val (nextState, alerts)         = spends.foldLeft((windowState, windowAlerts)) {
      case ((accState, accAlerts), (agentId, spend)) =>
        val (updatedState, newAlerts) = stepSpend(accState, agentId, spendLevelsOf(agentId), spend, now, messages)
        (updatedState, accAlerts ++ newAlerts)
    }
    val observed = observations.map { case (agentId, window, _) => WindowKey(agentId, window.id) }.toSet
    (nextState.prune(now, observed, spends.map(_._1).toSet), alerts)
  }

  private def stepWindow(
    state: AlertState,
    agentId: AgentId,
    levels: AlertLevels,
    window: UsageWindow,
    resetsAt: EpochSeconds,
    now: EpochSeconds,
    messages: Messages,
  ): (AlertState, List[Alert]) = {
    val key = WindowKey(agentId, window.id)

    val (record, resetAlerts, from) = state.records.get(key) match {
      case Some(existing) if isSameWindow(existing, window, resetsAt) =>
        (existing, List.empty[Alert], Format.wholePercent(existing.lastPercent))
      case Some(previous) =>
        val alerts =
          Option.when(previous.fired.exists(_.isLevel))(AlertText.reset(agentId, window, now, messages)).toList
        (WindowRecord(resetsAt, window.usedPercent, Set.empty[AlertKind]), alerts, 0L)
      case None =>
        (WindowRecord(resetsAt, window.usedPercent, Set.empty[AlertKind]), List.empty[Alert], 0L)
    }

    val crossed     = levels.crossed(from, Format.wholePercent(window.usedPercent))
    val fired       = record.fired ++ crossed.map(AlertKind.level)
    val levelAlerts = crossed
      .lastOption
      .filterNot(highest => record.fired.contains(AlertKind.level(highest)))
      .map(highest => AlertText.level(agentId, window, now, highest, messages))
      .toList

    val updated = WindowRecord(Order.max(record.resetsAt, resetsAt), window.usedPercent, fired)
    (state.updated(key, updated), resetAlerts ++ levelAlerts)
  }

  private def stepSpend(
    state: AlertState,
    agentId: AgentId,
    levels: SpendAlertLevels,
    spend: Spend,
    now: EpochSeconds,
    messages: Messages,
  ): (AlertState, List[Alert]) = {
    val (previousFired, resetAlerts, from, keptResetsAt) = state.spends.get(agentId) match {
      case Some(existing) if isSameLimit(existing, spend) && isSameMeter(existing, spend) =>
        val later = (existing.resetsAt, spend.resetsAt).mapN(Order.max).orElse(spend.resetsAt)
        (existing.fired, List.empty[Alert], existing.lastSpent, later)
      case Some(previous) if isSameLimit(previous, spend) =>
        val alerts =
          Option
            .when(!levels.isEmpty && previous.fired.exists(_.isLevel))(AlertText.spendReset(agentId, spend, messages))
            .toList
        (Set.empty[SpendAlertKind], alerts, Amount.zero, spend.resetsAt)
      case Some(_) | None =>
        (Set.empty[SpendAlertKind], List.empty[Alert], Amount.zero, spend.resetsAt)
    }

    val crossed     = levels.crossed(spend, from)
    val levelAlerts = crossed
      .lastOption
      .filterNot(previousFired.contains)
      .map(highest => AlertText.spendLevel(agentId, spend, now, highest, messages))
      .toList

    val updated = SpendRecord(spend.currency, spend.limit, keptResetsAt, spend.spent, previousFired ++ crossed, now)
    (state.updatedSpend(agentId, updated), resetAlerts ++ levelAlerts)
  }

  private def isSameWindow(record: WindowRecord, window: UsageWindow, resetsAt: EpochSeconds): Boolean =
    record.resetsAt.secondsUntil(resetsAt) <= ResetDriftTolerance.value &&
      window.usedPercent.value >= record.lastPercent.value - UsageDropTolerance

  /** A unit or limit change starts a new record from 0 with no reset alert (#105). */
  private def isSameLimit(record: SpendRecord, spend: Spend): Boolean =
    record.currency === spend.currency && record.limit === spend.limit

  /** The reset time moved by at most 120 s (when both are known) and the percent did not drop by more than 20 points. */
  private def isSameMeter(record: SpendRecord, spend: Spend): Boolean = {
    val sameReset   = (record.resetsAt, spend.resetsAt) match {
      case (Some(before), Some(after)) => before.secondsUntil(after) <= ResetDriftTolerance.value
      case _ => true
    }
    val lastPercent = spend.copy(spent = record.lastSpent).usedPercent
    sameReset && spend.usedPercent.value >= lastPercent.value - UsageDropTolerance
  }
}
