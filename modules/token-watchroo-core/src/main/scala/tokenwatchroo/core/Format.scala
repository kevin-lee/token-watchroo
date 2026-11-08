package tokenwatchroo.core

import cats.syntax.all.*

/** Text helpers for notification copy that do not depend on the language. Durations, spend summaries and other words
  * are in `Messages` (#10). Short menu labels and local times are rendered by the Swift shell.
  */
object Format {

  /** The shown whole percent. The colour bands use it too (#101), so the text and the colour always agree. */
  def wholePercent(p: UsedPercent): Long = math.round(p.value)

  /** "72%" */
  def percent(p: UsedPercent): String = s"${wholePercent(p)}%"

  /** "$246.00", "€17,000.50", "250.00 CHF" for a currency, "8,000" or "8,000.5" for credits (#105), with the
    * separators and the symbol placement of the macOS region (#10): "246,00 $" for German. Currencies have 2
    * decimals, credits are whole unless they have a fraction (at most 2 decimals), as `Formatting.money` in the Swift
    * shell. A currency without a symbol here shows its code after the number with a normal space.
    */
  def money(amount: Amount, currency: Currency, format: NumberFormat): String = currency match {
    case Currency.Iso(code) =>
      val number = grouped(amount.value.setScale(2, BigDecimal.RoundingMode.HALF_UP), format)
      CurrencySymbols.get(code.value).fold(s"$number ${code.value}")(symbol => placed(number, symbol, format))
    case Currency.Credits =>
      val rounded = amount.value.setScale(2, BigDecimal.RoundingMode.HALF_UP).bigDecimal.stripTrailingZeros
      grouped(BigDecimal(if (rounded.scale < 0) rounded.setScale(0) else rounded), format)
  }

  private val CurrencySymbols: Map[String, String] = Map("USD" -> "$", "EUR" -> "€", "GBP" -> "£", "JPY" -> "¥")

  private val NoBreakSpace: String = " "

  private def placed(number: String, symbol: String, format: NumberFormat): String = format.currencySymbol match {
    case SymbolPlacement.Before => s"$symbol$number"
    case SymbolPlacement.BeforeSpaced => s"$symbol$NoBreakSpace$number"
    case SymbolPlacement.After => s"$number$symbol"
    case SymbolPlacement.AfterSpaced => s"$number$NoBreakSpace$symbol"
  }

  /** The plain text of a non-negative decimal with the grouping separator between groups of 3 integer digits and the
    * decimal separator of `format`.
    */
  private def grouped(value: BigDecimal, format: NumberFormat): String = {
    val plain              = value.bigDecimal.toPlainString
    val (integer, decimal) = plain.span(_ =!= '.')
    val groupedInteger     = integer.reverse.grouped(3).toList.map(_.reverse).reverse.mkString(format.groupingSeparator)
    if (decimal.isEmpty) groupedInteger else s"$groupedInteger${format.decimalSeparator}${decimal.drop(1)}"
  }
}
