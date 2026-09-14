package tokenwatchroo.core

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*

final case class AgentSnapshot(
  id: AgentId,
  planLabel: Option[PlanLabel],
  status: AgentStatus,
  windows: List[UsageWindow],
  spend: Option[Spend],
  source: Option[Source],
  fetchedAt: EpochSeconds,
  error: Option[ErrorMessage],
) derives CanEqual,
      Eq,
      Show

object AgentSnapshot {

  /** A readable agent. `status` is derived from the windows and the spend. `error` carries a non-fatal note, such as
    * the API error when the local-log fallback was used.
    */
  def available(
    id: AgentId,
    planLabel: Option[PlanLabel],
    meters: UsageMeters,
    source: Source,
    fetchedAt: EpochSeconds,
    error: Option[ErrorMessage],
  ): AgentSnapshot =
    AgentSnapshot(
      id,
      planLabel,
      AgentStatus.of(meters.windows, meters.spend),
      meters.windows,
      meters.spend,
      source.some,
      fetchedAt,
      error,
    )

  /** An agent that could not be read. It carries no windows and no spend, so it can never look like 0% usage. */
  def unavailable(id: AgentId, fetchedAt: EpochSeconds, error: ErrorMessage): AgentSnapshot =
    AgentSnapshot(id, none[PlanLabel], AgentStatus.Unavailable, Nil, none[Spend], none[Source], fetchedAt, error.some)

  extension (snapshot: AgentSnapshot) {
    def isAvailable: Boolean = snapshot.status match {
      case AgentStatus.Ok | AgentStatus.Warning | AgentStatus.Critical | AgentStatus.Exhausted => true
      case AgentStatus.Unavailable => false
    }
  }
}
