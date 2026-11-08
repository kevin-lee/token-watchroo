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
  /** The agents from the settings window. An agent without an entry is auto-detected, so an empty list gives the
    * behaviour before #8. See `AgentChoice.of`.
    */
  agents: List[AgentConfig],
) derives CanEqual,
      Eq,
      Show {

  /** The config folder of the first `ClaudeCode` entry, the same first-entry rule as `AgentChoice.of` (#91). */
  def claudeConfigDir: Option[ClaudeConfigDir] =
    agents.find(_.id === AgentId.ClaudeCode).flatMap(_.claudeConfigDir)
}

object Config {
  def default(stateDir: StateDir): Config =
    Config(
      stateDir,
      RefreshIntervalSeconds.default,
      none[CodexHome],
      none[ClaudeCodeVersion],
      List.empty[AgentConfig],
    )
}
