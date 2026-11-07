package tokenwatchroo.core

import cats.syntax.all.*
import hedgehog.*
import hedgehog.runner.*

object UsageBandSpec extends Properties {

  override def tests: List[Test] = List(
    example("bands at the boundaries", testBoundaries),
    property("the band is monotonic in the percent", testMonotonic),
    property("the band follows the shown whole percent", testShownPercent),
  )

  private def band(p: Double): UsageBand = UsageBand.of(UsedPercent.clamp(p))

  def testBoundaries: Result =
    Result.all(
      List(
        band(0.0d) ==== UsageBand.Normal,
        band(50.0d) ==== UsageBand.Normal,
        band(50.4d) ==== UsageBand.Normal,
        band(50.5d) ==== UsageBand.Elevated,
        band(70.0d) ==== UsageBand.Elevated,
        band(70.5d) ==== UsageBand.Warning,
        band(85.0d) ==== UsageBand.Warning,
        band(85.5d) ==== UsageBand.Critical,
        band(100.0d) ==== UsageBand.Critical,
      )
    )

  def testMonotonic: Property =
    for {
      a <- Fixtures.genPercent.log("a")
      b <- Fixtures.genPercent.log("b")
    } yield {
      val (lo, hi) = if (a <= b) (a, b) else (b, a)
      Result.assert(UsageBand.of(hi).isAtLeast(UsageBand.of(lo))).log(s"lo=$lo hi=$hi")
    }

  def testShownPercent: Property =
    for {
      p <- Fixtures.genPercent.log("p")
    } yield UsageBand.of(p) ==== band(Format.wholePercent(p).toDouble)
}
