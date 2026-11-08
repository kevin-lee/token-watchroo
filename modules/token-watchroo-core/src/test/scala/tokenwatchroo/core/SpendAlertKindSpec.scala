package tokenwatchroo.core

import hedgehog.*
import hedgehog.runner.*

object SpendAlertKindSpec extends Properties {

  override def tests: List[Test] = List(
    property("wire and parse round-trip", testRoundTrip),
    example("the wire values follow the plan", testWire),
    example("parse rejects broken and window values", testRejects),
    property("no spend kind parses as a window kind", testNotWindow),
    property("no window kind parses as a spend kind", testNotSpend),
  )

  val genSpendAlertKind: Gen[SpendAlertKind] =
    Gen.choice1(
      Gen.int(Range.linear(1, 100)).map(n => SpendAlertKind.percent(AlertLevel.unsafeFrom(n))),
      Gen.long(Range.linear(1L, 10000000L)).map(n => SpendAlertKind.amount(SpendLevel.unsafeFrom(n))),
      Gen.constant(SpendAlertKind.reset),
    )

  val genAlertKind: Gen[AlertKind] =
    Gen.choice1(
      Gen.int(Range.linear(1, 100)).map(n => AlertKind.level(AlertLevel.unsafeFrom(n))),
      Gen.constant(AlertKind.reset),
    )

  def testRoundTrip: Property =
    genSpendAlertKind.log("kind").map(kind => SpendAlertKind.parse(kind.wire) ==== Right(kind))

  def testWire: Result =
    Result.all(
      List(
        SpendAlertKind.percent(AlertLevel.unsafeFrom(70)).wire ==== "spend-pct70",
        SpendAlertKind.amount(SpendLevel.unsafeFrom(150L)).wire ==== "spend-amt150",
        SpendAlertKind.reset.wire ==== "spend-reset",
      )
    )

  def testRejects: Result =
    Result.all(
      List("spend-pct0", "spend-pct101", "spend-amt0", "spend-amtx", "spend-amt-5", "threshold80", "level70", "reset")
        .map(s => Result.assert(SpendAlertKind.parse(s).isLeft).log(s))
    )

  def testNotWindow: Property =
    genSpendAlertKind.log("kind").map(kind => Result.assert(AlertKind.parse(kind.wire).isLeft))

  def testNotSpend: Property =
    genAlertKind.log("kind").map(kind => Result.assert(SpendAlertKind.parse(kind.wire).isLeft))
}
