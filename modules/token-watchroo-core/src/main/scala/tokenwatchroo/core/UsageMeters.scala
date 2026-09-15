package tokenwatchroo.core

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*

/** What a usage response maps to: the windows, the spend limit, or both. */
final case class UsageMeters(
  windows: List[UsageWindow],
  spend: Option[Spend],
) derives CanEqual,
      Eq,
      Show

object UsageMeters {

  val empty: UsageMeters = UsageMeters(Nil, none[Spend])

  extension (meters: UsageMeters) {

    /** Nothing to show: the agent must not look like 0% usage. */
    def isEmpty: Boolean = meters.windows.isEmpty && meters.spend.isEmpty
  }
}
