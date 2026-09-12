package tokenwatchroo.core

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
  )

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
