package tokenwatchroo.core

import cats.{Eq, Show}
import cats.derived.*

/** Result of a provider's credential probe. */
enum Detection derives CanEqual, Eq, Show {
  case Detected
  case NotDetected
}

object Detection {
  def detected: Detection    = Detection.Detected
  def notDetected: Detection = Detection.NotDetected

  def fromBoolean(present: Boolean): Detection = if (present) Detection.Detected else Detection.NotDetected

  extension (detection: Detection) {
    def isDetected: Boolean = detection match {
      case Detection.Detected => true
      case Detection.NotDetected => false
    }
  }
}
