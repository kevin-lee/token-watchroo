package tokenwatchroo.core

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*

/** The unit of a spend meter: an ISO currency for Claude, credits for Codex. */
enum Currency derives CanEqual, Eq, Show {
  case Iso(code: CurrencyCode)
  case Credits
}

object Currency {
  def iso(code: CurrencyCode): Currency = Currency.Iso(code)
  def credits: Currency                 = Currency.Credits

  private val CreditsWire: String = "credits"

  extension (currency: Currency) {
    def wire: String = currency match {
      case Currency.Iso(code) => code.value
      case Currency.Credits => CreditsWire
    }
  }

  /** `credits`, or an upper-case ISO 4217 code. Never trims, because the wire is the contract. */
  def parse(s: String): Either[String, Currency] =
    if (s === CreditsWire) Currency.credits.asRight[String]
    else CurrencyCode.from(s).map(Currency.iso).leftMap(_ => s"Unknown Currency: $s")
}
