package tokenwatchroo.core

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*
import extras.render.Render

enum WindowId derives CanEqual, Eq, Show {
  case Session
  case Weekly
}

object WindowId {
  def session: WindowId = WindowId.Session
  def weekly: WindowId  = WindowId.Weekly

  extension (windowId: WindowId) {
    def wire: String = windowId match {
      case WindowId.Session => "session"
      case WindowId.Weekly => "weekly"
    }

    /** Row label on a card. */
    def label: String = windowId match {
      case WindowId.Session => "Session"
      case WindowId.Weekly => "Weekly"
    }

    /** Noun phrase used in notification bodies. */
    def describe: String = windowId match {
      case WindowId.Session => "5 h window"
      case WindowId.Weekly => "weekly window"
    }
  }

  def parse(s: String): Either[String, WindowId] = s match {
    case "session" => WindowId.Session.asRight[String]
    case "weekly" => WindowId.Weekly.asRight[String]
    case unknown => s"Unknown WindowId: $unknown".asLeft[WindowId]
  }

  given render: Render[WindowId] = Render.render(_.label)
}
