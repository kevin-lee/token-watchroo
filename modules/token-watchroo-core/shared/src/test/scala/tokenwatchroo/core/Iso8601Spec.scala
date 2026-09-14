package tokenwatchroo.core

import cats.syntax.all.*
import hedgehog.*
import hedgehog.runner.*

object Iso8601Spec extends Properties {

  override def tests: List[Test] = List(
    property("format then parse round-trips every epoch between 1970 and 2100", testRoundTrip),
    example("Z and +00:00 are the same instant", testZeroOffset),
    example("a positive offset is subtracted", testPositiveOffset),
    example("a negative offset is added", testNegativeOffset),
    example("fractional seconds are ignored", testFractionalSeconds),
    example("known instants", testKnownInstants),
    example("garbage is rejected", testGarbage),
    property(
      "start of next UTC month is after now, at most 31 days later, and on day 1 at midnight",
      testStartOfNextUtcMonth,
    ),
    example("start of next UTC month for known instants", testStartOfNextUtcMonthKnown),
  )

  def testStartOfNextUtcMonth: Property =
    for {
      epoch <- Fixtures.genEpoch.log("epoch")
    } yield {
      val next = Iso8601.startOfNextUtcMonth(epoch)
      Result.all(
        List(
          Result.assert(next > epoch).log(s"next=$next"),
          Result.assert(epoch.secondsUntil(next) <= 31L * 86400L).log(s"next=$next"),
          Result.assert(Iso8601.format(next).endsWith("-01T00:00:00Z")).log(Iso8601.format(next)),
        )
      )
    }

  def testStartOfNextUtcMonthKnown: Result = {
    def next(s: String): Either[Iso8601Error, String] =
      Iso8601.parseToEpochSeconds(s).map(epoch => Iso8601.format(Iso8601.startOfNextUtcMonth(epoch)))
    Result.all(
      List(
        Iso8601.parseToEpochSeconds("2026-09-14T06:29:07Z").map(Iso8601.startOfNextUtcMonth) ==== Right(
          EpochSeconds(1790812800L)
        ),
        next("2026-10-01T00:00:00Z") ==== Right("2026-11-01T00:00:00Z"),
        next("2026-12-31T23:59:59Z") ==== Right("2027-01-01T00:00:00Z"),
        next("2028-02-29T12:00:00Z") ==== Right("2028-03-01T00:00:00Z"),
      )
    )
  }

  def testRoundTrip: Property =
    for {
      epoch <- Fixtures.genEpoch.log("epoch")
    } yield Iso8601.parseToEpochSeconds(Iso8601.format(epoch)) ==== Right(epoch)

  def testZeroOffset: Result =
    Iso8601.parseToEpochSeconds("2026-05-11T18:00:00+00:00") ==== Iso8601.parseToEpochSeconds("2026-05-11T18:00:00Z")

  def testPositiveOffset: Result =
    Iso8601.parseToEpochSeconds("2026-05-11T18:00:00+09:00") ==== Iso8601.parseToEpochSeconds("2026-05-11T09:00:00Z")

  def testNegativeOffset: Result =
    Iso8601.parseToEpochSeconds("2026-05-11T18:00:00-05:30") ==== Iso8601.parseToEpochSeconds("2026-05-11T23:30:00Z")

  def testFractionalSeconds: Result =
    Iso8601.parseToEpochSeconds("2026-05-11T18:00:00.123456Z") ==== Iso8601.parseToEpochSeconds("2026-05-11T18:00:00Z")

  def testKnownInstants: Result =
    Result.all(
      List(
        Iso8601.parseToEpochSeconds("1970-01-01T00:00:00Z") ==== Right(EpochSeconds(0L)),
        Iso8601.parseToEpochSeconds("2000-03-01T00:00:00Z") ==== Right(EpochSeconds(951868800L)),
        Iso8601.format(EpochSeconds(951868800L)) ==== "2000-03-01T00:00:00Z",
      )
    )

  def testGarbage: Result =
    Result.all(
      List(
        Iso8601.parseToEpochSeconds("not a date") ==== Left(Iso8601Error.Malformed("not a date")),
        Iso8601.parseToEpochSeconds("2026-13-01T00:00:00Z") ==== Left(Iso8601Error.Malformed("2026-13-01T00:00:00Z")),
        Iso8601.parseToEpochSeconds("2026-02-30T00:00:00Z") ==== Left(Iso8601Error.Malformed("2026-02-30T00:00:00Z")),
        Iso8601.parseToEpochSeconds("") ==== Left(Iso8601Error.Malformed("")),
      )
    )
}
