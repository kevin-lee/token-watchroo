package tokenwatchroo.core

import cats.{Eq, Show}
import cats.derived.*

final case class WindowKey(agent: AgentId, window: WindowId) derives CanEqual, Eq, Show

/** What has already been notified for one window of one agent. Keyed by agent and window, never by the raw reset
  * time, because Codex's `resets_at` drifts by a few seconds between polls.
  */
final case class WindowRecord(
  resetsAt: EpochSeconds,
  lastPercent: UsedPercent,
  fired: Set[AlertKind],
) derives CanEqual,
      Eq,
      Show

final case class AlertState(records: Map[WindowKey, WindowRecord]) derives CanEqual, Eq, Show

object AlertState {

  val empty: AlertState = AlertState(Map.empty)

  private val RetentionSeconds: Long = 8L * 86400L

  extension (state: AlertState) {

    /** Drops records whose window reset more than 8 days before `now`, except records of windows still being observed,
      * so a stale reset time from a provider can never cause repeated alerts.
      */
    def prune(now: EpochSeconds, observed: Set[WindowKey]): AlertState =
      AlertState(state.records.filter {
        case (key, record) =>
          observed.contains(key) || now.secondsUntil(record.resetsAt) >= -RetentionSeconds
      })

    def updated(key: WindowKey, record: WindowRecord): AlertState =
      AlertState(state.records.updated(key, record))
  }
}
