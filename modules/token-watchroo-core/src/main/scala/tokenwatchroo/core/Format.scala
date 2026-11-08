package tokenwatchroo.core

import cats.syntax.all.*

/** Text helpers for notification copy. Short menu labels and local times are rendered by the Swift shell. */
object Format {

  /** The shown whole percent. The colour bands use it too (#101), so the text and the colour always agree. */
  def wholePercent(p: UsedPercent): Long = math.round(p.value)

  /** "72%" */
  def percent(p: UsedPercent): String = s"${wholePercent(p)}%"

  /** "$246.00", "€17,000.50", "250.00 CHF" for a currency, "8,000" or "8,000.5" for credits (#105). Currencies have 2
    * decimals, credits are whole unless they have a fraction (at most 2 decimals), as `Formatting.money` in the Swift
    * shell. Groups of 3 digits use ",". #10 changes it for locales.
    */
  def money(amount: Amount, currency: Currency): String = currency match {
    case Currency.Iso(code) =>
      val number = grouped(amount.value.setScale(2, BigDecimal.RoundingMode.HALF_UP))
      CurrencySymbols.get(code.value).fold(s"$number ${code.value}")(symbol => s"$symbol$number")
    case Currency.Credits =>
      val rounded = amount.value.setScale(2, BigDecimal.RoundingMode.HALF_UP).bigDecimal.stripTrailingZeros
      grouped(BigDecimal(if (rounded.scale < 0) rounded.setScale(0) else rounded))
  }

  /** "$246.00 of $300.00 spent", or "8,000 of 10,000 credits spent". */
  def spendSummary(spend: Spend): String = {
    val spent = money(spend.spent, spend.currency)
    val limit = money(spend.limit, spend.currency)
    spend.currency match {
      case Currency.Credits => s"$spent of $limit credits spent"
      case Currency.Iso(_) => s"$spent of $limit spent"
    }
  }

  private val CurrencySymbols: Map[String, String] = Map("USD" -> "$", "EUR" -> "€", "GBP" -> "£", "JPY" -> "¥")

  /** The plain text of a non-negative decimal with "," between groups of 3 integer digits. */
  private def grouped(value: BigDecimal): String = {
    val plain              = value.bigDecimal.toPlainString
    val (integer, decimal) = plain.span(_ =!= '.')
    integer.reverse.grouped(3).mkString(",").reverse + decimal
  }

  /** "under a minute", "11 minutes", "1 hour 12 minutes", "2 days 3 hours" */
  def longDuration(seconds: Seconds): String = {
    val total   = math.max(0L, seconds.value)
    val days    = total / 86400L
    val hours   = (total % 86400L) / 3600L
    val minutes = (total % 3600L) / 60L
    if (total < 60L) "under a minute"
    else if (days > 0L) joinUnits(unit(days, "day"), unit(hours, "hour"))
    else if (hours > 0L) joinUnits(unit(hours, "hour"), unit(minutes, "minute"))
    else unit(minutes, "minute")
  }

  private def unit(n: Long, name: String): String = if (n === 1L) s"1 $name" else s"$n ${name}s"

  private def joinUnits(major: String, minor: String): String =
    if (minor.startsWith("0 ")) major else s"$major $minor"
}
