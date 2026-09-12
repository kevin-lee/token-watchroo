package tokenwatchroo.core

import cats.syntax.all.*
import extras.render.Render
import hedgehog.*
import hedgehog.runner.*
import refined4s.types.all.*

object WindowIdSpec extends Properties {

  override def tests: List[Test] = List(
    property("wire round-trips through parse for every case", testRoundTrip),
    example("wire, label, describe, and limit noun per case", testTexts),
    example("parse keeps the name verbatim", testVerbatim),
    example("unknown and empty wires are rejected", testRejected),
    example("ModelName.fromDisplayName trims and rejects empty", testFromDisplayName),
    property("Render of a WindowId is its label", testRender),
  )

  private def model(name: String): WindowId = WindowId.model(ModelName(NonEmptyString.unsafeFrom(name)))

  def testRoundTrip: Property =
    for {
      id <- Fixtures.genWindowId.log("id")
    } yield WindowId.parse(id.wire) ==== Right(id)

  def testTexts: Result =
    Result.all(
      List(
        WindowId.Session.wire ==== "session",
        WindowId.Session.label ==== "Session",
        WindowId.Session.describe ==== "5 h window",
        WindowId.Session.limitNoun ==== "session",
        WindowId.Weekly.wire ==== "weekly",
        WindowId.Weekly.label ==== "Weekly",
        WindowId.Weekly.describe ==== "weekly window",
        WindowId.Weekly.limitNoun ==== "weekly",
        model("Fable").wire ==== "weekly-model:Fable",
        model("Fable").label ==== "Weekly (Fable)",
        model("Fable").describe ==== "weekly Fable window",
        model("Fable").limitNoun ==== "weekly Fable",
        model("Opus").wire ==== "weekly-model:Opus",
        model("Sonnet 4.5").wire ==== "weekly-model:Sonnet 4.5",
        model("Sonnet 4.5").label ==== "Weekly (Sonnet 4.5)",
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

  def testRender: Property =
    for {
      id <- Fixtures.genWindowId.log("id")
    } yield Render[WindowId].render(id) ==== id.label
}
