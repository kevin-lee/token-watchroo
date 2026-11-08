package tokenwatchroo.core

import hedgehog.*
import hedgehog.runner.*

object FormatSpec extends Properties {

  override def tests: List[Test] = List(
    example("percent rounds to the nearest integer", testPercent),
    example("long durations follow the design copy", testLongDuration),
    property("percent always ends with a percent sign and holds no decimals", testPercentShape),
    example("money uses a symbol or the code for currencies and whole numbers for credits", testMoney),
    example("spendSummary follows the card text", testSpendSummary),
  )

  private def iso(code: String): Currency = Currency.iso(CurrencyCode.unsafeFrom(code))

  def testMoney: Result =
    Result.all(
      List(
        Format.money(Fixtures.amount("0.05"), iso("USD")) ==== "$0.05",
        Format.money(Fixtures.amount("17000.5"), iso("USD")) ==== "$17,000.50",
        Format.money(Fixtures.amount("246"), iso("EUR")) ==== "€246.00",
        Format.money(Fixtures.amount("1500"), iso("JPY")) ==== "¥1,500.00",
        Format.money(Fixtures.amount("250"), iso("CHF")) ==== "250.00 CHF",
        Format.money(Fixtures.amount("1234567.891"), iso("GBP")) ==== "£1,234,567.89",
        Format.money(Fixtures.amount("8000"), Currency.credits) ==== "8,000",
        Format.money(Fixtures.amount("8000.50"), Currency.credits) ==== "8,000.5",
        Format.money(Fixtures.amount("0"), Currency.credits) ==== "0",
        Format.money(Fixtures.amount("999.999"), Currency.credits) ==== "1,000",
      )
    )

  def testSpendSummary: Result =
    Result.all(
      List(
        Format.spendSummary(Fixtures.spend(iso("USD"), "246", "300", None)) ==== "$246.00 of $300.00 spent",
        Format.spendSummary(
          Fixtures.spend(Currency.credits, "8000", "10000", None)
        ) ==== "8,000 of 10,000 credits spent",
      )
    )

  def testPercent: Result =
    Result.all(
      List(
        Format.percent(UsedPercent.clamp(72.4d)) ==== "72%",
        Format.percent(UsedPercent.clamp(71.5d)) ==== "72%",
        Format.percent(UsedPercent.clamp(0.0d)) ==== "0%",
        Format.percent(UsedPercent.clamp(100.0d)) ==== "100%",
      )
    )

  def testLongDuration: Result =
    Result.all(
      List(
        Format.longDuration(Seconds(0L)) ==== "under a minute",
        Format.longDuration(Seconds(59L)) ==== "under a minute",
        Format.longDuration(Seconds(60L)) ==== "1 minute",
        Format.longDuration(Seconds(660L)) ==== "11 minutes",
        Format.longDuration(Seconds(3600L)) ==== "1 hour",
        Format.longDuration(Seconds(4320L)) ==== "1 hour 12 minutes",
        Format.longDuration(Seconds(86400L)) ==== "1 day",
        Format.longDuration(Seconds(183600L)) ==== "2 days 3 hours",
        Format.longDuration(Seconds(-5L)) ==== "under a minute",
      )
    )

  def testPercentShape: Property =
    Fixtures.genPercent.log("p").map { p =>
      val s = Format.percent(p)
      Result.all(List(Result.assert(s.endsWith("%")), Result.assert(!s.contains("."))))
    }
}
