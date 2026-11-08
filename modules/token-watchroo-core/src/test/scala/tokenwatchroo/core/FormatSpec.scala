package tokenwatchroo.core

import hedgehog.*
import hedgehog.runner.*

object FormatSpec extends Properties {

  override def tests: List[Test] = List(
    example("percent rounds to the nearest integer", testPercent),
    example("long durations follow the design copy (Messages, #10)", testLongDuration),
    property("percent always ends with a percent sign and holds no decimals", testPercentShape),
    example("money uses a symbol or the code for currencies and whole numbers for credits", testMoney),
    example("spendSummary follows the card text (Messages, #10)", testSpendSummary),
    example("money follows the separators and the symbol placement of the region (#10)", testMoneyRegion),
    example(
      "NumberFormat.from rejects separators that cannot be told apart from digits or each other",
      testNumberFormatFrom
    ),
  )

  private val german: NumberFormat =
    NumberFormat.from(",", ".", SymbolPlacement.AfterSpaced).fold(sys.error, identity)

  private def money(amount: String, currency: Currency): String =
    Format.money(Fixtures.amount(amount), currency, NumberFormat.default)

  private def iso(code: String): Currency = Currency.iso(CurrencyCode.unsafeFrom(code))

  def testMoney: Result =
    Result.all(
      List(
        money("0.05", iso("USD")) ==== "$0.05",
        money("17000.5", iso("USD")) ==== "$17,000.50",
        money("246", iso("EUR")) ==== "€246.00",
        money("1500", iso("JPY")) ==== "¥1,500.00",
        money("250", iso("CHF")) ==== "250.00 CHF",
        money("1234567.891", iso("GBP")) ==== "£1,234,567.89",
        money("8000", Currency.credits) ==== "8,000",
        money("8000.50", Currency.credits) ==== "8,000.5",
        money("0", Currency.credits) ==== "0",
        money("999.999", Currency.credits) ==== "1,000",
      )
    )

  def testSpendSummary: Result =
    Result.all(
      List(
        TestMessages.en.spendSummary(Fixtures.spend(iso("USD"), "246", "300", None)) ==== "$246.00 of $300.00 spent",
        TestMessages
          .en
          .spendSummary(
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
        TestMessages.en.duration(Seconds(0L)) ==== "under a minute",
        TestMessages.en.duration(Seconds(59L)) ==== "under a minute",
        TestMessages.en.duration(Seconds(60L)) ==== "1 minute",
        TestMessages.en.duration(Seconds(660L)) ==== "11 minutes",
        TestMessages.en.duration(Seconds(3600L)) ==== "1 hour",
        TestMessages.en.duration(Seconds(4320L)) ==== "1 hour 12 minutes",
        TestMessages.en.duration(Seconds(86400L)) ==== "1 day",
        TestMessages.en.duration(Seconds(183600L)) ==== "2 days 3 hours",
        TestMessages.en.duration(Seconds(-5L)) ==== "under a minute",
      )
    )

  def testMoneyRegion: Result = {
    val beforeSpaced = NumberFormat.from(".", ",", SymbolPlacement.BeforeSpaced).fold(sys.error, identity)
    Result.all(
      List(
        Format.money(Fixtures.amount("246"), iso("USD"), german) ==== "246,00\u00a0$",
        Format.money(Fixtures.amount("17000.5"), iso("EUR"), german) ==== "17.000,50\u00a0€",
        Format.money(Fixtures.amount("250"), iso("CHF"), german) ==== "250,00 CHF",
        Format.money(Fixtures.amount("8000.50"), Currency.credits, german) ==== "8.000,5",
        Format.money(Fixtures.amount("246"), iso("USD"), beforeSpaced) ==== "$\u00a0246.00",
      )
    )
  }

  def testNumberFormatFrom: Result =
    Result.all(
      List(
        NumberFormat.from(".", ".", SymbolPlacement.Before).isLeft ==== true,
        NumberFormat.from("1", ",", SymbolPlacement.Before).isLeft ==== true,
        NumberFormat.from("", ",", SymbolPlacement.Before).isLeft ==== true,
        NumberFormat.from(".", "", SymbolPlacement.Before).isLeft ==== true,
        NumberFormat.from(".", "\u00a0", SymbolPlacement.After).isRight ==== true,
      )
    )

  def testPercentShape: Property =
    Fixtures.genPercent.log("p").map { p =>
      val s = Format.percent(p)
      Result.all(List(Result.assert(s.endsWith("%")), Result.assert(!s.contains("."))))
    }
}
