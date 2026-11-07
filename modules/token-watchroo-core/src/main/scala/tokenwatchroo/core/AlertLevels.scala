package tokenwatchroo.core

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*
import refined4s.*
import refined4s.modules.cats.derivation.*

/** A window alert level (#9): a whole percent from 1 to 100, compared with the shown whole percent. */
type AlertLevel = AlertLevel.Type
object AlertLevel extends Refined[Int], CatsEqShow[Int], CatsOrder[Int] {
  override inline def invalidReason(a: Int): String = expectedMessage("a whole percent from 1 to 100")
  override inline def predicate(a: Int): Boolean    = a >= 1 && a <= 100
}

/** The window alert levels of one agent (#9): up to 5, strictly increasing, at least 5 apart. Empty means no window
  * alerts. Alarm-clock rule: a level fires when the shown whole percent crosses it, that is the last shown percent of
  * the same window is below the level and the new one is at or above it. A window with no record, or a new window after
  * a reset, starts from 0. See `AlertEngine`.
  */
final case class AlertLevels private (levels: List[AlertLevel]) derives CanEqual, Eq, Show

object AlertLevels {

  val MaxCount: Int = 5
  val MinGap: Int   = 5

  /** 70 and 85, where the amber and the red bands start (#101). */
  val defaults: AlertLevels = AlertLevels(List(AlertLevel.unsafeFrom(70), AlertLevel.unsafeFrom(85)))

  val none: AlertLevels = AlertLevels(List.empty[AlertLevel])

  def from(levels: List[Int]): Either[String, AlertLevels] =
    for {
      _       <- Either.cond(levels.length <= MaxCount, (), s"Up to $MaxCount levels: ${levels.mkString(", ")}")
      checked <- levels.traverse(AlertLevel.from)
      _       <- Either.cond(
                   levels.zip(levels.drop(1)).forall { case (a, b) => b - a >= MinGap },
                   (),
                   s"Levels must be at least $MinGap apart: ${levels.mkString(", ")}",
                 )
    } yield AlertLevels(checked)

  /** The levels of the first entry for `id` in `Config.agents`, else the defaults. Other entries with the same `id`
    * are ignored, as in `AgentChoice.of`.
    */
  def of(config: Config, id: AgentId): AlertLevels =
    config.agents.find(_.id === id).fold(defaults)(_.alertLevels)

  extension (alertLevels: AlertLevels) {

    /** The levels with `from < level <= to`, in increasing order. */
    def crossed(from: Long, to: Long): List[AlertLevel] =
      alertLevels.levels.filter(level => from < level.value.toLong && level.value.toLong <= to)
  }
}
