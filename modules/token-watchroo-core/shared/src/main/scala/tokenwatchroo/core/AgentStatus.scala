package tokenwatchroo.core

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*

enum AgentStatus derives CanEqual, Eq, Show {
  case Ok
  case Warning
  case Critical
  case Exhausted
  case Unavailable
}

object AgentStatus {
  def ok: AgentStatus          = AgentStatus.Ok
  def warning: AgentStatus     = AgentStatus.Warning
  def critical: AgentStatus    = AgentStatus.Critical
  def exhausted: AgentStatus   = AgentStatus.Exhausted
  def unavailable: AgentStatus = AgentStatus.Unavailable

  extension (status: AgentStatus) {
    def wire: String = status match {
      case AgentStatus.Ok => "ok"
      case AgentStatus.Warning => "warning"
      case AgentStatus.Critical => "critical"
      case AgentStatus.Exhausted => "exhausted"
      case AgentStatus.Unavailable => "unavailable"
    }
  }

  def parse(s: String): Either[String, AgentStatus] = s match {
    case "ok" => AgentStatus.Ok.asRight[String]
    case "warning" => AgentStatus.Warning.asRight[String]
    case "critical" => AgentStatus.Critical.asRight[String]
    case "exhausted" => AgentStatus.Exhausted.asRight[String]
    case "unavailable" => AgentStatus.Unavailable.asRight[String]
    case unknown => s"Unknown AgentStatus: $unknown".asLeft[AgentStatus]
  }

  /** Status rules: no windows is Unavailable, any exhausted window is Exhausted, then the thresholds on the maximum. */
  def of(windows: List[UsageWindow]): AgentStatus = windows match {
    case Nil => AgentStatus.Unavailable
    case first :: rest =>
      val maxPercent = rest.foldLeft(first.usedPercent)((acc, w) => if (w.usedPercent > acc) w.usedPercent else acc)
      if (windows.exists(_.isExhausted)) AgentStatus.Exhausted
      else if (maxPercent >= Thresholds.critical) AgentStatus.Critical
      else if (maxPercent >= Thresholds.warning) AgentStatus.Warning
      else AgentStatus.Ok
  }
}
