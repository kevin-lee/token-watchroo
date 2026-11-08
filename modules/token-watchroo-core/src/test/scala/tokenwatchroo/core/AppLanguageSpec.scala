package tokenwatchroo.core

import cats.syntax.all.*
import hedgehog.*
import hedgehog.runner.*

object AppLanguageSpec extends Properties {

  override def tests: List[Test] = List(
    example("parse accepts en, ko and region tags in any letter case", testAccepted),
    example("parse rejects other languages and an empty text", testRejected),
    example("fromWire gives English for a missing or unknown value", testFromWire),
    property("wire round-trips through parse", testRoundTrip),
  )

  def testAccepted: Result =
    Result.all(
      List(
        AppLanguage.parse("en") ==== AppLanguage.En.asRight[String],
        AppLanguage.parse("ko") ==== AppLanguage.Ko.asRight[String],
        AppLanguage.parse("en-AU") ==== AppLanguage.En.asRight[String],
        AppLanguage.parse("ko-KR") ==== AppLanguage.Ko.asRight[String],
        AppLanguage.parse("KO") ==== AppLanguage.Ko.asRight[String],
      )
    )

  def testRejected: Result =
    Result.all(
      List(
        AppLanguage.parse("fr") ==== "Unknown AppLanguage: fr".asLeft[AppLanguage],
        AppLanguage.parse("").isLeft ==== true,
        AppLanguage.parse("kor").isLeft ==== true,
      )
    )

  def testFromWire: Result =
    Result.all(
      List(
        AppLanguage.fromWire(none[String]) ==== AppLanguage.En,
        AppLanguage.fromWire("ja".some) ==== AppLanguage.En,
        AppLanguage.fromWire("ko".some) ==== AppLanguage.Ko,
      )
    )

  def testRoundTrip: Property =
    Gen.elementUnsafe(AppLanguage.all).log("language").map { language =>
      AppLanguage.parse(language.wire) ==== language.asRight[String]
    }
}
