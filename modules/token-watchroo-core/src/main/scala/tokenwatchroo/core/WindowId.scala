package tokenwatchroo.core

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*
import refined4s.types.all.*

/** Session and Weekly always exist for every agent and may be idle. `Model` is a Claude per-model weekly window that
  * exists only while the API reports a running window, is never idle, and counts towards the status and the menubar
  * like any other window.
  */
enum WindowId derives CanEqual, Eq, Show {
  case Session
  case Weekly
  case Model(name: ModelName)
}

object WindowId {
  def session: WindowId                = WindowId.Session
  def weekly: WindowId                 = WindowId.Weekly
  def model(name: ModelName): WindowId = WindowId.Model(name)

  private val ModelPrefix: String = "weekly-model:"

  extension (windowId: WindowId) {
    def wire: String = windowId match {
      case WindowId.Session => "session"
      case WindowId.Weekly => "weekly"
      case WindowId.Model(name) => s"$ModelPrefix${name.value.value}"
    }
  }

  /** `session`, `weekly`, or `weekly-model:<name>` split at the first colon with a non-empty name. Never trims. */
  def parse(s: String): Either[String, WindowId] = s match {
    case "session" => WindowId.Session.asRight[String]
    case "weekly" => WindowId.Weekly.asRight[String]
    case prefixed if prefixed.startsWith(ModelPrefix) =>
      NonEmptyString
        .from(prefixed.drop(ModelPrefix.length))
        .fold(_ => unknown(prefixed), name => WindowId.model(ModelName(name)).asRight[String])
    case other => unknown(other)
  }

  private def unknown(s: String): Either[String, WindowId] = s"Unknown WindowId: $s".asLeft[WindowId]
}
