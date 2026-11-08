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

/** What has already been notified for the spend of one agent (#105). One spend per agent, so the key is the agent.
  * `currency` and `limit` show a unit or limit change, which starts a new record from 0 with no reset alert.
  * `lastSpent` is where the next crossing starts. `seenAt` is when the spend was last observed, so a record of a spend
  * with no reset time can be pruned.
  */
final case class SpendRecord(
  currency: Currency,
  limit: Amount,
  resetsAt: Option[EpochSeconds],
  lastSpent: Amount,
  fired: Set[SpendAlertKind],
  seenAt: EpochSeconds,
) derives CanEqual,
      Eq,
      Show

final case class AlertState(records: Map[WindowKey, WindowRecord], spends: Map[AgentId, SpendRecord])
    derives CanEqual,
      Eq,
      Show

object AlertState {

  val empty: AlertState = AlertState(Map.empty, Map.empty)

  private val RetentionSeconds: Long = 8L * 86400L

  extension (state: AlertState) {

    /** Drops records whose window reset more than 8 days before `now`, except records of windows still being observed,
      * so a stale reset time from a provider can never cause repeated alerts. Drops spend records that were not
      * observed for more than 8 days, except spends still being observed (#105).
      */
    def prune(now: EpochSeconds, observed: Set[WindowKey], observedSpends: Set[AgentId]): AlertState =
      AlertState(
        state.records.filter {
          case (key, record) =>
            observed.contains(key) || now.secondsUntil(record.resetsAt) >= -RetentionSeconds
        },
        state.spends.filter {
          case (agent, record) =>
            observedSpends.contains(agent) || now.secondsUntil(record.seenAt) >= -RetentionSeconds
        },
      )

    def updated(key: WindowKey, record: WindowRecord): AlertState =
      state.copy(records = state.records.updated(key, record))

    def updatedSpend(agent: AgentId, record: SpendRecord): AlertState =
      state.copy(spends = state.spends.updated(agent, record))
  }
}
