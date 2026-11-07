package tokenwatchroo.core

/** Decides which notifications to fire for a snapshot. Pure, deterministic, and idempotent: applying the same snapshot
  * twice emits nothing the second time. Windows that expired at `now` are not observed, so stale values never fire
  * (issue #81). Their records stay until `prune` drops them, so the reset alert still fires when the next window
  * arrives.
  *
  * Alarm-clock rule (#9): a level of the agent fires when the shown whole percent crosses it, from the last shown
  * percent of the same window (0 for a window with no record or a new window) to the shown percent now. Only the highest
  * crossed level fires, and all crossed levels are marked as fired, so each level fires at most once per window. A
  * level change never fires by itself.
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
  ): (AlertState, List[Alert]) = {
    val observations =
      for {
        agent    <- snapshot.agents.filter(_.isAvailable)
        window   <- agent.windows.filterNot(_.isExpiredAt(now))
        resetsAt <- window.resetsAt.toList
      } yield (agent.id, window, resetsAt)

    val (nextState, alerts) = observations.foldLeft((state, List.empty[Alert])) {
      case ((accState, accAlerts), (agentId, window, resetsAt)) =>
        val (updatedState, newAlerts) = stepWindow(accState, agentId, levelsOf(agentId), window, resetsAt, now)
        (updatedState, accAlerts ++ newAlerts)
    }
    val observed            = observations.map { case (agentId, window, _) => WindowKey(agentId, window.id) }.toSet
    (nextState.prune(now, observed), alerts)
  }

  private def stepWindow(
    state: AlertState,
    agentId: AgentId,
    levels: AlertLevels,
    window: UsageWindow,
    resetsAt: EpochSeconds,
    now: EpochSeconds,
  ): (AlertState, List[Alert]) = {
    val key = WindowKey(agentId, window.id)

    val (record, resetAlerts, from) = state.records.get(key) match {
      case Some(existing) if isSameWindow(existing, window, resetsAt) =>
        (existing, List.empty[Alert], Format.wholePercent(existing.lastPercent))
      case Some(previous) =>
        val crossedLevel = previous.fired.exists(_.isLevel)
        val alreadyReset = previous.fired.contains(AlertKind.Reset)
        val alerts       = Option.when(crossedLevel && !alreadyReset)(AlertText.reset(agentId, window, now)).toList
        val fired        = if (alerts.nonEmpty) Set[AlertKind](AlertKind.Reset) else Set.empty[AlertKind]
        (WindowRecord(resetsAt, window.usedPercent, fired), alerts, 0L)
      case None =>
        (WindowRecord(resetsAt, window.usedPercent, Set.empty[AlertKind]), List.empty[Alert], 0L)
    }

    val crossed     = levels.crossed(from, Format.wholePercent(window.usedPercent))
    val fired       = record.fired ++ crossed.map(AlertKind.level)
    val levelAlerts = crossed
      .lastOption
      .filterNot(highest => record.fired.contains(AlertKind.level(highest)))
      .map(highest => AlertText.level(agentId, window, now, highest))
      .toList

    val updated = WindowRecord(resetsAt, window.usedPercent, fired)
    (state.updated(key, updated), resetAlerts ++ levelAlerts)
  }

  private def isSameWindow(record: WindowRecord, window: UsageWindow, resetsAt: EpochSeconds): Boolean =
    record.resetsAt.secondsUntil(resetsAt) <= ResetDriftTolerance.value &&
      window.usedPercent.value >= record.lastPercent.value - UsageDropTolerance
}
