package tokenwatchroo.core

import cats.{Eq, Show}
import cats.derived.*

/** A notification to post. `usedPercent` is the window's percent at the moment the alert was built, so the shell can
  * draw the ring on the notification. For a `Reset` alert it is the new window's percent.
  */
final case class Alert(
  agent: AgentId,
  window: WindowId,
  kind: AlertKind,
  windowResetsAt: EpochSeconds,
  usedPercent: UsedPercent,
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
