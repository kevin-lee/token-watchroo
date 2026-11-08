package tokenwatchroo.core

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*
import refined4s.*
import refined4s.modules.cats.derivation.*

/** A spend alert amount (#105): a whole amount of 1 or more in the meter's unit (the currency or credits). */
type SpendLevel = SpendLevel.Type
object SpendLevel extends Refined[Long], CatsEqShow[Long], CatsOrder[Long] {
  override inline def invalidReason(a: Long): String = expectedMessage("a whole amount of 1 or more")
  override inline def predicate(a: Long): Boolean    = a >= 1L
}

/** The spend alert levels of one agent (#105).
  *
  *   - `Defaults`: 70 and 85 percent of the current limit, on the shown whole percent of the spend, the same rule as the
  *     window levels (#9). So the colour band and the alert change at the same refresh, and the levels follow the
  *     limit.
  *   - `Amounts`: up to 5 whole amounts in `unit`, strictly increasing, compared with the exact spent amount. An empty
  *     list means no spend alerts. When `unit` is not the meter's unit, a non-empty list gives the defaults, because an
  *     amount means nothing in another unit.
  *
  * Alarm-clock rule: a level fires when the spend crosses it, see `AlertEngine`.
  */
enum SpendAlertLevels derives CanEqual, Eq, Show {
  case Defaults
  case Amounts(unit: Currency, amounts: List[SpendLevel])
}

object SpendAlertLevels {

  val MaxCount: Int = 5

  val defaults: SpendAlertLevels = SpendAlertLevels.Defaults

  def from(unit: Currency, amounts: List[Long]): Either[String, SpendAlertLevels] =
    for {
      _       <- Either.cond(amounts.length <= MaxCount, (), s"Up to $MaxCount amounts: ${amounts.mkString(", ")}")
      checked <- amounts.traverse(SpendLevel.from)
      _       <- Either.cond(
                   amounts.zip(amounts.drop(1)).forall { case (a, b) => b > a },
                   (),
                   s"Amounts must be strictly increasing: ${amounts.mkString(", ")}",
                 )
    } yield SpendAlertLevels.Amounts(unit, checked)

  /** The spend levels of the first entry for `id` in `Config.agents`, else the defaults, as in `AlertLevels.of`. */
  def of(config: Config, id: AgentId): SpendAlertLevels =
    config.agents.find(_.id === id).fold(defaults)(_.spendAlertLevels)

  extension (levels: SpendAlertLevels) {

    /** No level applies to the spend: an empty list of amounts. */
    def isEmpty: Boolean = levels match {
      case SpendAlertLevels.Defaults => false
      case SpendAlertLevels.Amounts(_, amounts) => amounts.isEmpty
    }

    /** The levels crossed from `fromSpent` to `spend.spent`, in increasing order: `from < level <= now`. */
    def crossed(spend: Spend, fromSpent: Amount): List[SpendAlertKind] = levels match {
      case SpendAlertLevels.Amounts(_, Nil) => Nil
      case SpendAlertLevels.Amounts(unit, amounts) if unit === spend.currency =>
        amounts
          .filter { amount =>
            val value = BigDecimal(amount.value)
            fromSpent.value < value && value <= spend.spent.value
          }
          .map(SpendAlertKind.amount)
      case SpendAlertLevels.Defaults | SpendAlertLevels.Amounts(_, _) =>
        val from = Format.wholePercent(spend.copy(spent = fromSpent).usedPercent)
        AlertLevels.defaults.crossed(from, Format.wholePercent(spend.usedPercent)).map(SpendAlertKind.percent)
    }
  }
}
