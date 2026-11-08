package tokenwatchroo.core

import cats.syntax.all.*
import hedgehog.*
import hedgehog.runner.*
import refined4s.types.all.*

object WindowIdSpec extends Properties {

  override def tests: List[Test] = List(
    property("wire round-trips through parse for every case", testRoundTrip),
    example("wire per case (the words are in MessagesSpec, #10)", testTexts),
    example("parse keeps the name verbatim", testVerbatim),
    example("unknown and empty wires are rejected", testRejected),
    example("ModelName.fromDisplayName trims and rejects empty", testFromDisplayName),
  )

  private def model(name: String): WindowId = WindowId.model(ModelName(NonEmptyString.unsafeFrom(name)))

  def testRoundTrip: Property =
    Fixtures.genWindowId.log("id").map { id =>
      WindowId.parse(id.wire) ==== Right(id)
    }

  def testTexts: Result =
    Result.all(
      List(
        WindowId.Session.wire ==== "session",
        WindowId.Weekly.wire ==== "weekly",
        model("Fable").wire ==== "weekly-model:Fable",
        model("Opus").wire ==== "weekly-model:Opus",
        model("Sonnet 4.5").wire ==== "weekly-model:Sonnet 4.5",
      )
    )

  def testVerbatim: Result =
    Result.all(
      List(
        WindowId.parse("weekly-model:Sonnet 4.5") ==== Right(model("Sonnet 4.5")),
        WindowId.parse("weekly-model: Fable") ==== Right(model(" Fable")),
        WindowId.parse("weekly-model:Fable").map(_.wire) ==== Right("weekly-model:Fable"),
      )
    )

  def testRejected: Result =
    Result.all(
      List(
        WindowId.parse("weekly-model:").isLeft ==== true,
        WindowId.parse("weekly-model").isLeft ==== true,
        WindowId.parse("monthly").isLeft ==== true,
        WindowId.parse("").isLeft ==== true,
        WindowId.parse("Session").isLeft ==== true,
        WindowId.parse("weekly-model:") ==== Left("Unknown WindowId: weekly-model:"),
      )
    )

  def testFromDisplayName: Result =
    Result.all(
      List(
        ModelName.fromDisplayName(" Fable ") ==== Some(ModelName(NonEmptyString("Fable"))),
        ModelName.fromDisplayName("") ==== none[ModelName],
        ModelName.fromDisplayName("  ") ==== none[ModelName],
      )
    )
}
