package tokenwatchroo.core

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*

enum AgentStatus derives CanEqual, Eq, Show {
  case Ok
  case Elevated
  case Warning
  case Critical
  case Exhausted
  case Unavailable
}

object AgentStatus {
  def ok: AgentStatus          = AgentStatus.Ok
  def elevated: AgentStatus    = AgentStatus.Elevated
  def warning: AgentStatus     = AgentStatus.Warning
  def critical: AgentStatus    = AgentStatus.Critical
  def exhausted: AgentStatus   = AgentStatus.Exhausted
  def unavailable: AgentStatus = AgentStatus.Unavailable

  extension (status: AgentStatus) {
    def wire: String = status match {
      case AgentStatus.Ok => "ok"
      case AgentStatus.Elevated => "elevated"
      case AgentStatus.Warning => "warning"
      case AgentStatus.Critical => "critical"
      case AgentStatus.Exhausted => "exhausted"
      case AgentStatus.Unavailable => "unavailable"
    }
  }

  def parse(s: String): Either[String, AgentStatus] = s match {
    case "ok" => AgentStatus.Ok.asRight[String]
    case "elevated" => AgentStatus.Elevated.asRight[String]
    case "warning" => AgentStatus.Warning.asRight[String]
    case "critical" => AgentStatus.Critical.asRight[String]
    case "exhausted" => AgentStatus.Exhausted.asRight[String]
    case "unavailable" => AgentStatus.Unavailable.asRight[String]
    case unknown => s"Unknown AgentStatus: $unknown".asLeft[AgentStatus]
  }

  /** Status rules: no windows and no spend is Unavailable, any exhausted window or an exhausted spend is Exhausted,
    * then the colour band (`UsageBand`, #101) of the maximum over the window percents and the spend percent: Ok,
    * Elevated, Warning or Critical.
    */
  def of(windows: List[UsageWindow], spend: Option[Spend]): AgentStatus =
    (windows.map(_.usedPercent) ++ spend.map(_.usedPercent).toList) match {
      case Nil => AgentStatus.Unavailable
      case first :: rest =>
        val maxPercent = rest.foldLeft(first)((acc, p) => if (p > acc) p else acc)
        if (windows.exists(_.isExhausted) || spend.exists(_.isExhausted)) AgentStatus.Exhausted
        else
          UsageBand.of(maxPercent) match {
            case UsageBand.Normal => AgentStatus.Ok
            case UsageBand.Elevated => AgentStatus.Elevated
            case UsageBand.Warning => AgentStatus.Warning
            case UsageBand.Critical => AgentStatus.Critical
          }
    }

  /** `of` over the windows that have not expired at `now`. */
  def at(windows: List[UsageWindow], spend: Option[Spend], now: EpochSeconds): AgentStatus =
    of(windows.filterNot(_.isExpiredAt(now)), spend)
}
