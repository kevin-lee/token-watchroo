package tokenwatchroo.core

import cats.{Eq, Show}
import cats.derived.*

/** Why a fetch runs. `Scheduled` is the timer, the first tick, and a config change. `Manual` is the user's "Refresh
  * now" and bypasses provider caches. It never crosses the FFI or JSON, so it has no `wire`.
  */
enum FetchTrigger derives CanEqual, Eq, Show {
  case Scheduled
  case Manual
}

object FetchTrigger {
  def scheduled: FetchTrigger = FetchTrigger.Scheduled
  def manual: FetchTrigger    = FetchTrigger.Manual
}
