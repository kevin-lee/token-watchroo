package tokenwatchroo.core

import cats.{Eq, Show}
import cats.derived.*

/** What the library sends to the Swift shell through the callback. */
enum Envelope derives CanEqual, Eq, Show {
  case Snapshot(seq: SequenceNumber, data: tokenwatchroo.core.Snapshot)
  case Alert(seq: SequenceNumber, data: tokenwatchroo.core.Alert)
  case Error(seq: SequenceNumber, message: String)
}

object Envelope {

  /** Bumped only on a breaking change of the JSON contract. */
  val Version: Int = 1

  def snapshot(seq: SequenceNumber, data: tokenwatchroo.core.Snapshot): Envelope = Envelope.Snapshot(seq, data)
  def alert(seq: SequenceNumber, data: tokenwatchroo.core.Alert): Envelope       = Envelope.Alert(seq, data)
  def error(seq: SequenceNumber, message: String): Envelope                      = Envelope.Error(seq, message)

  extension (envelope: Envelope) {
    def seq: SequenceNumber = envelope match {
      case Envelope.Snapshot(seq, _) => seq
      case Envelope.Alert(seq, _) => seq
      case Envelope.Error(seq, _) => seq
    }

    def wire: String = envelope match {
      case Envelope.Snapshot(_, _) => "snapshot"
      case Envelope.Alert(_, _) => "alert"
      case Envelope.Error(_, _) => "error"
    }
  }
}

/** JSON shape of an alert: the identifier is added so the shell never has to compute it. */
final case class AlertPayload(
  agent: AgentId,
  window: WindowId,
  kind: AlertKind,
  windowResetsAt: EpochSeconds,
  title: String,
  body: String,
  identifier: String,
) derives CanEqual,
      Eq,
      Show

object AlertPayload {
  def fromAlert(alert: tokenwatchroo.core.Alert): AlertPayload =
    AlertPayload(alert.agent, alert.window, alert.kind, alert.windowResetsAt, alert.title, alert.body, alert.identifier)

  extension (payload: AlertPayload) {
    def toAlert: tokenwatchroo.core.Alert =
      tokenwatchroo
        .core
        .Alert(payload.agent, payload.window, payload.kind, payload.windowResetsAt, payload.title, payload.body)
  }
}
