package tokenwatchroo.app

import cats.{Eq, Show}
import cats.derived.*
import tokenwatchroo.core.*

/** What the host can ask the poller to do. */
enum Command derives CanEqual, Eq, Show {
  case Refresh
  case SetConfig(config: Config)
  case Shutdown
}

object Command {
  def refresh: Command                   = Command.Refresh
  def setConfig(config: Config): Command = Command.SetConfig(config)
  def shutdown: Command                  = Command.Shutdown
}
