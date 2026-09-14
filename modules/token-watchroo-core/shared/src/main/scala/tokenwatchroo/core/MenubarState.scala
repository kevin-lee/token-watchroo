package tokenwatchroo.core

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*

enum MenubarKind derives CanEqual, Eq, Show {
  case Normal
  case Warning
  case Critical
  case Exhausted
  case Unavailable
}

object MenubarKind {
  def normal: MenubarKind      = MenubarKind.Normal
  def warning: MenubarKind     = MenubarKind.Warning
  def critical: MenubarKind    = MenubarKind.Critical
  def exhausted: MenubarKind   = MenubarKind.Exhausted
  def unavailable: MenubarKind = MenubarKind.Unavailable

  extension (kind: MenubarKind) {
    def wire: String = kind match {
      case MenubarKind.Normal => "normal"
      case MenubarKind.Warning => "warning"
      case MenubarKind.Critical => "critical"
      case MenubarKind.Exhausted => "exhausted"
      case MenubarKind.Unavailable => "unavailable"
    }
  }

  def parse(s: String): Either[String, MenubarKind] = s match {
    case "normal" => MenubarKind.Normal.asRight[String]
    case "warning" => MenubarKind.Warning.asRight[String]
    case "critical" => MenubarKind.Critical.asRight[String]
    case "exhausted" => MenubarKind.Exhausted.asRight[String]
    case "unavailable" => MenubarKind.Unavailable.asRight[String]
    case unknown => s"Unknown MenubarKind: $unknown".asLeft[MenubarKind]
  }
}

/** What the menubar item shows. The label text is rendered by the Swift shell from these values. */
final case class MenubarState(
  kind: MenubarKind,
  usedPercent: Option[UsedPercent],
  resetsAt: Option[EpochSeconds],
) derives CanEqual,
      Eq,
      Show

object MenubarState {

  val unavailable: MenubarState = MenubarState(MenubarKind.Unavailable, none[UsedPercent], none[EpochSeconds])

  /** The ring shows the highest usage across the windows and spends of the available agents. An exhausted window or
    * spend wins and carries the soonest reset among the exhausted ones.
    */
  def derive(agents: List[AgentSnapshot]): MenubarState = {
    val available = agents.filter(_.isAvailable)
    val windows   = available.flatMap(_.windows)
    val spends    = available.flatMap(_.spend.toList)
    (windows.map(_.usedPercent) ++ spends.map(_.usedPercent)) match {
      case Nil => unavailable
      case first :: rest =>
        val maxPercent      = rest.foldLeft(first)((acc, p) => if (p > acc) p else acc)
        val anyExhausted    = windows.exists(_.isExhausted) || spends.exists(_.isExhausted)
        val exhaustedResets =
          windows.filter(_.isExhausted).flatMap(_.resetsAt) ++ spends.filter(_.isExhausted).flatMap(_.resetsAt)
        exhaustedResets match {
          case soonest :: others =>
            val earliest = others.foldLeft(soonest)((acc, r) => if (r < acc) r else acc)
            MenubarState(MenubarKind.Exhausted, maxPercent.some, earliest.some)
          case Nil =>
            if (anyExhausted) MenubarState(MenubarKind.Exhausted, maxPercent.some, none[EpochSeconds])
            else if (maxPercent >= Thresholds.critical)
              MenubarState(MenubarKind.Critical, maxPercent.some, none[EpochSeconds])
            else if (maxPercent >= Thresholds.warning)
              MenubarState(MenubarKind.Warning, maxPercent.some, none[EpochSeconds])
            else MenubarState(MenubarKind.Normal, maxPercent.some, none[EpochSeconds])
        }
    }
  }
}
