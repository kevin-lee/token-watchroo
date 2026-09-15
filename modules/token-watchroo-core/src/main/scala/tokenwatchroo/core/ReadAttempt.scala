package tokenwatchroo.core

import cats.{Eq, Show}
import cats.derived.*

/** Selects the keychain timeout: the first read may have the "Always Allow" dialog open. */
enum ReadAttempt derives CanEqual, Eq, Show {
  case First
  case Subsequent
}

object ReadAttempt {
  def first: ReadAttempt      = ReadAttempt.First
  def subsequent: ReadAttempt = ReadAttempt.Subsequent
}
