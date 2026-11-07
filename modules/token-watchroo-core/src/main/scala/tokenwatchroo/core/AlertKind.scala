package tokenwatchroo.core

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*

/** A level alert (#9) or a reset alert. The wire value of level 70 is `threshold80` and of level 85 is `threshold95`,
  * names kept from before #101 because they are stored in `state.json` and used in notification identifiers, so a user
  * with the default levels keeps the state of 0.2.x. Any other level `n` is `level<n>`.
  */
enum AlertKind derives CanEqual, Eq, Show {
  case Level(level: AlertLevel)
  case Reset
}

object AlertKind {
  def level(level: AlertLevel): AlertKind = AlertKind.Level(level)
  def reset: AlertKind                    = AlertKind.Reset

  private val LevelPrefix: String = "level"

  extension (kind: AlertKind) {
    def wire: String = kind match {
      case AlertKind.Level(level) =>
        level.value match {
          case 70 => "threshold80"
          case 85 => "threshold95"
          case n => s"$LevelPrefix$n"
        }
      case AlertKind.Reset => "reset"
    }

    def isLevel: Boolean = kind match {
      case AlertKind.Level(_) => true
      case AlertKind.Reset => false
    }
  }

  def parse(s: String): Either[String, AlertKind] = s match {
    case "threshold80" => AlertKind.Level(AlertLevel.unsafeFrom(70)).asRight[String]
    case "threshold95" => AlertKind.Level(AlertLevel.unsafeFrom(85)).asRight[String]
    case "reset" => AlertKind.Reset.asRight[String]
    case level if level.startsWith(LevelPrefix) =>
      level
        .drop(LevelPrefix.length)
        .toIntOption
        .toRight(s"Unknown AlertKind: $level")
        .flatMap(n => AlertLevel.from(n).leftMap(reason => s"Unknown AlertKind: $level ($reason)"))
        .map(AlertKind.Level(_))
    case unknown => s"Unknown AlertKind: $unknown".asLeft[AlertKind]
  }
}
