package tokenwatchroo.core

import cats.{Eq, Show}
import cats.derived.*

final case class Snapshot(
  updatedAt: EpochSeconds,
  agents: List[AgentSnapshot],
  menubar: MenubarState,
) derives CanEqual,
      Eq,
      Show

object Snapshot {
  def of(updatedAt: EpochSeconds, agents: List[AgentSnapshot]): Snapshot =
    Snapshot(updatedAt, agents, MenubarState.derive(agents))
}
