package tokenwatchroo.core

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*

/** What an alert is about: a usage window with its kind and reset time, or the spend of the agent (#105), which has no
  * `WindowId` and can have no reset time. On the wire a spend alert has the `window` value `spend`, which
  * `WindowId.parse` never accepts, and no `windowResetsAt` when the spend has no reset time.
  */
enum AlertSubject derives CanEqual, Eq, Show {
  case Window(window: WindowId, kind: AlertKind, resetsAt: EpochSeconds)
  case Spend(kind: SpendAlertKind, resetsAt: Option[EpochSeconds])
}

object AlertSubject {
  def window(window: WindowId, kind: AlertKind, resetsAt: EpochSeconds): AlertSubject =
    AlertSubject.Window(window, kind, resetsAt)

  def spend(kind: SpendAlertKind, resetsAt: Option[EpochSeconds]): AlertSubject = AlertSubject.Spend(kind, resetsAt)

  val SpendWire: String = "spend"

  extension (subject: AlertSubject) {

    /** The `window` value of the alert JSON. */
    def windowWire: String = subject match {
      case AlertSubject.Window(window, _, _) => window.wire
      case AlertSubject.Spend(_, _) => SpendWire
    }

    def kindWire: String = subject match {
      case AlertSubject.Window(_, kind, _) => kind.wire
      case AlertSubject.Spend(kind, _) => kind.wire
    }

    /** The `windowResetsAt` value of the alert JSON, absent for a spend with no reset time. */
    def resetsAtWire: Option[EpochSeconds] = subject match {
      case AlertSubject.Window(_, _, resetsAt) => resetsAt.some
      case AlertSubject.Spend(_, resetsAt) => resetsAt
    }
  }
}
