package tokenwatchroo.core

import cats.syntax.all.*
import hedgehog.*
import hedgehog.runner.*
import refined4s.types.all.*

object SpendAlertLevelsSpec extends Properties {

  override def tests: List[Test] = List(
    example("from accepts no amounts, two amounts and five amounts", testAccepts),
    example("from rejects 0, a negative, six amounts, a duplicate and a decrease", testRejects),
    property("every accepted list obeys the rules", testAcceptedObeyRules),
    property("generated valid lists are accepted", testValidAccepted),
    example("the defaults cross 70 and 85 percent on the shown whole percent", testCrossedDefaults),
    example("amounts cross on the exact spent amount", testCrossedAmounts),
    example("amounts in another unit give the defaults, no amounts give nothing", testCrossedUnit),
    example("of uses the first entry for the agent, else the defaults", testOf),
  )

  private val credits: Currency = Currency.credits

  private def amounts(levels: SpendAlertLevels): List[Long] = levels match {
    case SpendAlertLevels.Defaults => Nil
    case SpendAlertLevels.Amounts(_, values) => values.map(_.value)
  }

  private def percent(value: Int): SpendAlertKind = SpendAlertKind.percent(AlertLevel.unsafeFrom(value))
  private def amount(value: Long): SpendAlertKind = SpendAlertKind.amount(SpendLevel.unsafeFrom(value))

  def testAccepts: Result =
    Result.all(
      List(
        SpendAlertLevels.from(Fixtures.usd, Nil) ==== Right(SpendAlertLevels.Amounts(Fixtures.usd, Nil)),
        SpendAlertLevels.from(Fixtures.usd, List(150L, 180L)).map(amounts) ==== Right(List(150L, 180L)),
        SpendAlertLevels.from(credits, List(1L, 2L, 3L, 4L, 5L)).map(amounts) ==== Right(List(1L, 2L, 3L, 4L, 5L)),
      )
    )

  def testRejects: Result =
    Result.all(
      List(
        List(0L),
        List(-5L),
        List(1L, 2L, 3L, 4L, 5L, 6L),
        List(150L, 150L),
        List(180L, 150L),
      ).map(values => SpendAlertLevels.from(Fixtures.usd, values).isLeft ==== true)
    )

  def testAcceptedObeyRules: Property =
    Gen.long(Range.linear(-10L, 1000L)).list(Range.linear(0, 7)).log("amounts").map { values =>
      SpendAlertLevels.from(credits, values) match {
        case Right(levels) =>
          val accepted = amounts(levels)
          Result.all(
            List(
              Result.assert(accepted.length <= SpendAlertLevels.MaxCount),
              Result.assert(accepted.forall(_ >= 1L)),
              Result.assert(accepted.zip(accepted.drop(1)).forall { case (a, b) => b > a }),
            )
          )
        case Left(_) => Result.success
      }
    }

  def testValidAccepted: Property =
    Fixtures.genSpendAmountValues.log("amounts").map { values =>
      SpendAlertLevels.from(credits, values).map(amounts) ==== Right(values)
    }

  def testCrossedDefaults: Result = {
    val defaults                                   = SpendAlertLevels.defaults
    def at(spent: String): Spend                   = Fixtures.spend(Fixtures.usd, spent, "200", none[EpochSeconds])
    def from0(spent: String): List[SpendAlertKind] = defaults.crossed(at(spent), Amount.zero)
    Result.all(
      List(
        from0("140") ==== List(percent(70)),
        from0("139.40") ==== List(percent(70)),
        from0("138.90") ==== Nil,
        from0("180") ==== List(percent(70), percent(85)),
        defaults.crossed(at("180"), Fixtures.amount("140")) ==== List(percent(85)),
      )
    )
  }

  def testCrossedAmounts: Result = {
    val levels                   = SpendAlertLevels.from(Fixtures.usd, List(150L, 180L)).fold(sys.error, identity)
    def at(spent: String): Spend = Fixtures.spend(Fixtures.usd, spent, "200", none[EpochSeconds])
    Result.all(
      List(
        levels.crossed(at("150"), Fixtures.amount("149.99")) ==== List(amount(150L)),
        levels.crossed(at("149.99"), Amount.zero) ==== Nil,
        levels.crossed(at("190"), Amount.zero) ==== List(amount(150L), amount(180L)),
        levels.crossed(at("190"), Fixtures.amount("150")) ==== List(amount(180L)),
      )
    )
  }

  def testCrossedUnit: Result = {
    val eur      = Currency.iso(CurrencyCode.unsafeFrom("EUR"))
    val inEuro   = SpendAlertLevels.from(eur, List(150L)).fold(sys.error, identity)
    val noLevels = SpendAlertLevels.from(Fixtures.usd, Nil).fold(sys.error, identity)
    val spend    = Fixtures.spend(Fixtures.usd, "145", "200", none[EpochSeconds])
    Result.all(
      List(
        inEuro.crossed(spend, Amount.zero) ==== List(percent(70)),
        noLevels.crossed(spend, Amount.zero) ==== Nil,
        Result.assert(noLevels.isEmpty),
        Result.assert(!inEuro.isEmpty),
        Result.assert(!SpendAlertLevels.defaults.isEmpty),
      )
    )
  }

  def testOf: Result = {
    val custom = SpendAlertLevels.from(credits, List(100L)).fold(sys.error, identity)
    val first  = AgentConfig.defaults(AgentId.Codex).copy(spendAlertLevels = custom)
    val second = AgentConfig.defaults(AgentId.Codex)
    val config = Config.default(StateDir(NonEmptyString("/tmp/tw"))).copy(agents = List(first, second))
    Result.all(
      List(
        SpendAlertLevels.of(config, AgentId.Codex) ==== custom,
        SpendAlertLevels.of(config, AgentId.ClaudeCode) ==== SpendAlertLevels.defaults,
      )
    )
  }
}
