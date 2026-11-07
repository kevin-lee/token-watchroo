package tokenwatchroo.core

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*

/** The wire values `threshold80` and `threshold95` are kept from before #101, because they are stored in `state.json`
  * and used in notification identifiers. They mean the warning alert (over 70) and the critical alert (over 85), not
  * those percents.
  */
enum AlertKind derives CanEqual, Eq, Show {
  case Warning
  case Critical
  case Reset
}

object AlertKind {
  def warning: AlertKind  = AlertKind.Warning
  def critical: AlertKind = AlertKind.Critical
  def reset: AlertKind    = AlertKind.Reset

  extension (kind: AlertKind) {
    def wire: String = kind match {
      case AlertKind.Warning => "threshold80"
      case AlertKind.Critical => "threshold95"
      case AlertKind.Reset => "reset"
    }
  }

  def parse(s: String): Either[String, AlertKind] = s match {
    case "threshold80" => AlertKind.Warning.asRight[String]
    case "threshold95" => AlertKind.Critical.asRight[String]
    case "reset" => AlertKind.Reset.asRight[String]
    case unknown => s"Unknown AlertKind: $unknown".asLeft[AlertKind]
  }
}
