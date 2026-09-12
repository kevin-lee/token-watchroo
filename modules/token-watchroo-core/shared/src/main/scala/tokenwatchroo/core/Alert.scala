package tokenwatchroo.core

import cats.{Eq, Show}
import cats.derived.*

final case class Alert(
  agent: AgentId,
  window: WindowId,
  kind: AlertKind,
  windowResetsAt: EpochSeconds,
  title: String,
  body: String,
) derives CanEqual,
      Eq,
      Show

object Alert {
  extension (alert: Alert) {

    /** Notification identifier. macOS replaces a pending notification with the same identifier. */
    def identifier: String =
      s"${alert.agent.wire}.${alert.window.wire}.${alert.windowResetsAt.value}.${alert.kind.wire}"
  }
}
