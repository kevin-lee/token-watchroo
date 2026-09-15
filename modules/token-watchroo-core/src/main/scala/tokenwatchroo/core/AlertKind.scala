package tokenwatchroo.core

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*

enum AlertKind derives CanEqual, Eq, Show {
  case Warning80
  case Critical95
  case Reset
}

object AlertKind {
  def warning80: AlertKind  = AlertKind.Warning80
  def critical95: AlertKind = AlertKind.Critical95
  def reset: AlertKind      = AlertKind.Reset

  extension (kind: AlertKind) {
    def wire: String = kind match {
      case AlertKind.Warning80 => "threshold80"
      case AlertKind.Critical95 => "threshold95"
      case AlertKind.Reset => "reset"
    }
  }

  def parse(s: String): Either[String, AlertKind] = s match {
    case "threshold80" => AlertKind.Warning80.asRight[String]
    case "threshold95" => AlertKind.Critical95.asRight[String]
    case "reset" => AlertKind.Reset.asRight[String]
    case unknown => s"Unknown AlertKind: $unknown".asLeft[AlertKind]
  }
}
