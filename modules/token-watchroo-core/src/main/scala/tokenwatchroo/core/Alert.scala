package tokenwatchroo.core

import cats.{Eq, Show}
import cats.derived.*

/** A notification to post. `usedPercent` is the window's percent, or the spend's percent of its limit (#105), at the
  * moment the alert was built, so the shell can draw the ring on the notification. For a reset alert it is the new
  * window's or the new spend meter's percent.
  */
final case class Alert(
  agent: AgentId,
  subject: AlertSubject,
  usedPercent: UsedPercent,
  title: String,
  body: String,
) derives CanEqual,
      Eq,
      Show

object Alert {
  extension (alert: Alert) {

    /** Notification identifier: `<agent>.<window>.<resetsAt>.<kind>`, with `spend` as the window and `none` as the
      * reset time for a spend with no reset time (#105). macOS replaces a pending notification with the same
      * identifier.
      */
    def identifier: String = {
      val resetsAt = alert.subject.resetsAtWire.fold("none")(_.value.toString)
      s"${alert.agent.wire}.${alert.subject.windowWire}.$resetsAt.${alert.subject.kindWire}"
    }
  }
}
