package tokenwatchroo.core

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*

/** Where the currency symbol goes in a money text (#10), from the macOS region. The spaced placements put a no-break
  * space (U+00A0) between the number and the symbol, as Foundation does.
  */
enum SymbolPlacement derives CanEqual, Eq, Show {
  case Before
  case BeforeSpaced
  case After
  case AfterSpaced
}

object SymbolPlacement {
  def before: SymbolPlacement       = SymbolPlacement.Before
  def beforeSpaced: SymbolPlacement = SymbolPlacement.BeforeSpaced
  def after: SymbolPlacement        = SymbolPlacement.After
  def afterSpaced: SymbolPlacement  = SymbolPlacement.AfterSpaced

  extension (placement: SymbolPlacement) {
    def wire: String = placement match {
      case SymbolPlacement.Before => "before"
      case SymbolPlacement.BeforeSpaced => "before-spaced"
      case SymbolPlacement.After => "after"
      case SymbolPlacement.AfterSpaced => "after-spaced"
    }
  }

  def parse(s: String): Either[String, SymbolPlacement] = s match {
    case "before" => SymbolPlacement.Before.asRight[String]
    case "before-spaced" => SymbolPlacement.BeforeSpaced.asRight[String]
    case "after" => SymbolPlacement.After.asRight[String]
    case "after-spaced" => SymbolPlacement.AfterSpaced.asRight[String]
    case unknown => s"Unknown SymbolPlacement: $unknown".asLeft[SymbolPlacement]
  }
}

/** The number rules of the macOS region (#10), sent by the shell, because Scala Native's javalib has no locale data.
  * `default` gives the text of the versions before #10: "$246.00", "8,000".
  */
final case class NumberFormat private (
  decimalSeparator: String,
  groupingSeparator: String,
  currencySymbol: SymbolPlacement,
) derives CanEqual,
      Eq,
      Show

object NumberFormat {

  val default: NumberFormat = NumberFormat(".", ",", SymbolPlacement.Before)

  /** Each separator has 1 to 3 characters and no digit, and the two separators are different. */
  def from(
    decimalSeparator: String,
    groupingSeparator: String,
    currencySymbol: SymbolPlacement,
  ): Either[String, NumberFormat] =
    for {
      _ <- validSeparator("decimal", decimalSeparator)
      _ <- validSeparator("grouping", groupingSeparator)
      _ <- Either.cond(
             decimalSeparator =!= groupingSeparator,
             (),
             s"The decimal and grouping separators are the same: $decimalSeparator",
           )
    } yield NumberFormat(decimalSeparator, groupingSeparator, currencySymbol)

  private def validSeparator(name: String, separator: String): Either[String, Unit] =
    Either.cond(
      separator.nonEmpty && separator.length <= 3 && !separator.exists(_.isDigit),
      (),
      s"Invalid $name separator: [$separator]",
    )
}
