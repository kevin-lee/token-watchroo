package tokenwatchroo.core

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*

/** A spend limit, such as a Claude Enterprise monthly limit or a Codex per-user credit limit. The currency applies to
  * both amounts, so a spend can never mix currencies.
  */
final case class Spend(
  currency: Currency,
  spent: Amount,
  limit: Amount,
  resetsAt: Option[EpochSeconds],
) derives CanEqual,
      Eq,
      Show

object Spend {

  extension (spend: Spend) {

    /** Computed from the amounts, so 0.05 of 200 is 0.025. A zero limit leaves nothing to spend, which is 100. */
    def usedPercent: UsedPercent =
      if (spend.limit.value.signum === 0) UsedPercent.clamp(100.0d)
      else UsedPercent.clamp((spend.spent.value / spend.limit.value * 100).toDouble)

    def isExhausted: Boolean = spend.spent.value >= spend.limit.value
  }
}
