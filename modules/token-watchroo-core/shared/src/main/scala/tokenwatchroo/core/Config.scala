package tokenwatchroo.core

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*

/** Runtime configuration supplied by the Swift shell as JSON. */
final case class Config(
  stateDir: StateDir,
  refreshInterval: RefreshIntervalSeconds,
  codexHome: Option[CodexHome],
  claudeCodeVersionOverride: Option[ClaudeCodeVersion],
) derives CanEqual,
      Eq,
      Show

object Config {
  def default(stateDir: StateDir): Config =
    Config(stateDir, RefreshIntervalSeconds.default, none[CodexHome], none[ClaudeCodeVersion])
}
