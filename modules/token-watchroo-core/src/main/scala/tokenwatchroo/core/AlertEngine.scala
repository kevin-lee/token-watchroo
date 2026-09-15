package tokenwatchroo.core

import cats.syntax.all.*

/** Decides which notifications to fire for a snapshot. Pure, deterministic, and idempotent: applying the same snapshot
  * twice emits nothing the second time.
  */
object AlertEngine {

  /** Reset-time drift tolerated before a window counts as new. */
  val ResetDriftTolerance: Seconds = Seconds(120L)

  /** Usage drop tolerated before a window counts as new. */
  val UsageDropTolerance: Double = 20.0d

  def step(state: AlertState, snapshot: Snapshot, now: EpochSeconds): (AlertState, List[Alert]) = {
    val observations =
      for {
        agent    <- snapshot.agents.filter(_.isAvailable)
        window   <- agent.windows
        resetsAt <- window.resetsAt.toList
      } yield (agent.id, window, resetsAt)

    val (nextState, alerts) = observations.foldLeft((state, List.empty[Alert])) {
      case ((accState, accAlerts), (agentId, window, resetsAt)) =>
        val (updatedState, newAlerts) = stepWindow(accState, agentId, window, resetsAt, now)
        (updatedState, accAlerts ++ newAlerts)
    }
    val observed            = observations.map { case (agentId, window, _) => WindowKey(agentId, window.id) }.toSet
    (nextState.prune(now, observed), alerts)
  }

  private def stepWindow(
    state: AlertState,
    agentId: AgentId,
    window: UsageWindow,
    resetsAt: EpochSeconds,
    now: EpochSeconds,
  ): (AlertState, List[Alert]) = {
    val key = WindowKey(agentId, window.id)

    val (record, resetAlerts) = state.records.get(key) match {
      case Some(existing) if isSameWindow(existing, window, resetsAt) =>
        (existing, List.empty[Alert])
      case Some(previous) =>
        val crossedThreshold =
          previous.fired.contains(AlertKind.Warning80) || previous.fired.contains(AlertKind.Critical95)
        val alreadyReset     = previous.fired.contains(AlertKind.Reset)
        val alerts = Option.when(crossedThreshold && !alreadyReset)(AlertText.reset(agentId, window, now)).toList
        val fired  = if (alerts.nonEmpty) Set[AlertKind](AlertKind.Reset) else Set.empty[AlertKind]
        (WindowRecord(resetsAt, window.usedPercent, fired), alerts)
      case None =>
        (WindowRecord(resetsAt, window.usedPercent, Set.empty[AlertKind]), List.empty[Alert])
    }

    val (fired, thresholdAlerts) =
      if (window.usedPercent >= Thresholds.critical && !record.fired.contains(AlertKind.Critical95)) {
        (record.fired + AlertKind.Critical95 + AlertKind.Warning80, List(AlertText.critical95(agentId, window, now)))
      } else if (window.usedPercent >= Thresholds.warning && !record.fired.contains(AlertKind.Warning80)) {
        (record.fired + AlertKind.Warning80, List(AlertText.warning80(agentId, window, now)))
      } else {
        (record.fired, List.empty[Alert])
      }

    val updated = WindowRecord(resetsAt, window.usedPercent, fired)
    (state.updated(key, updated), resetAlerts ++ thresholdAlerts)
  }

  private def isSameWindow(record: WindowRecord, window: UsageWindow, resetsAt: EpochSeconds): Boolean =
    record.resetsAt.secondsUntil(resetsAt) <= ResetDriftTolerance.value &&
      window.usedPercent.value >= record.lastPercent.value - UsageDropTolerance
}
