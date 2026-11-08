package tokenwatchroo.core

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*

/** A spend alert (#105): a percent level of the limit (the defaults), an amount level, or a reset. The wire values are
  * `spend-pct<n>`, `spend-amt<n>` and `spend-reset`. None of them parses as an `AlertKind`, so a spend kind can never
  * get into a window record, and a reader that knows only window kinds skips it.
  */
enum SpendAlertKind derives CanEqual, Eq, Show {
  case Percent(level: AlertLevel)
  case Amount(amount: SpendLevel)
  case Reset
}

object SpendAlertKind {
  def percent(level: AlertLevel): SpendAlertKind = SpendAlertKind.Percent(level)
  def amount(amount: SpendLevel): SpendAlertKind = SpendAlertKind.Amount(amount)
  def reset: SpendAlertKind                      = SpendAlertKind.Reset

  private val PercentPrefix: String = "spend-pct"
  private val AmountPrefix: String  = "spend-amt"
  private val ResetWire: String     = "spend-reset"

  extension (kind: SpendAlertKind) {
    def wire: String = kind match {
      case SpendAlertKind.Percent(level) => s"$PercentPrefix${level.value}"
      case SpendAlertKind.Amount(amount) => s"$AmountPrefix${amount.value}"
      case SpendAlertKind.Reset => ResetWire
    }

    def isLevel: Boolean = kind match {
      case SpendAlertKind.Percent(_) | SpendAlertKind.Amount(_) => true
      case SpendAlertKind.Reset => false
    }
  }

  def parse(s: String): Either[String, SpendAlertKind] = {
    def unknown(reason: String): String = s"Unknown SpendAlertKind: $s ($reason)"
    s match {
      case ResetWire => SpendAlertKind.Reset.asRight[String]
      case percent if percent.startsWith(PercentPrefix) =>
        percent
          .drop(PercentPrefix.length)
          .toIntOption
          .toRight(unknown("not a number"))
          .flatMap(n => AlertLevel.from(n).leftMap(unknown))
          .map(SpendAlertKind.Percent(_))
      case amount if amount.startsWith(AmountPrefix) =>
        amount
          .drop(AmountPrefix.length)
          .toLongOption
          .toRight(unknown("not a number"))
          .flatMap(n => SpendLevel.from(n).leftMap(unknown))
          .map(SpendAlertKind.Amount(_))
      case other => s"Unknown SpendAlertKind: $other".asLeft[SpendAlertKind]
    }
  }
}
