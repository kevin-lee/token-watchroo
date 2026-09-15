package tokenwatchroo.core

import hedgehog.*
import hedgehog.runner.*

object FormatSpec extends Properties {

  override def tests: List[Test] = List(
    example("percent rounds to the nearest integer", testPercent),
    example("long durations follow the design copy", testLongDuration),
    property("percent always ends with a percent sign and holds no decimals", testPercentShape),
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
    for {
      p <- Fixtures.genPercent.log("p")
    } yield {
      val s = Format.percent(p)
      Result.all(List(Result.assert(s.endsWith("%")), Result.assert(!s.contains("."))))
    }
}
