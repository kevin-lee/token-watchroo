package tokenwatchroo.core

import cats.syntax.all.*
import hedgehog.*
import hedgehog.runner.*
import refined4s.types.all.*

object AgentStatusSpec extends Properties {

  override def tests: List[Test] = List(
    property("status is monotonic in the maximum percent", testMonotonic),
    example("thresholds", testThresholds),
    example("no windows is unavailable", testEmpty),
    property("per-model windows count towards the status", testPerModel),
    example("spend thresholds", testSpendThresholds),
    example("no windows and no spend is unavailable", testNoWindowsNoSpend),
    property("status over windows and spend equals the status over the maximum percent", testWindowsAndSpend),
  )

  private val now = EpochSeconds(1789185600L)

  private def rank(status: AgentStatus): Int = status match {
    case AgentStatus.Unavailable => -1
    case AgentStatus.Ok => 0
    case AgentStatus.Warning => 1
    case AgentStatus.Critical => 2
    case AgentStatus.Exhausted => 3
  }

  private def ofWindows(windows: List[UsageWindow]): AgentStatus = AgentStatus.of(windows, none[Spend])

  def testMonotonic: Property =
    for {
      a <- Fixtures.genPercent.log("a")
      b <- Fixtures.genPercent.log("b")
    } yield {
      val (lo, hi) = if (a <= b) (a, b) else (b, a)
      val statusLo = ofWindows(List(Fixtures.window(WindowId.Session, lo.value, now.some)))
      val statusHi = ofWindows(List(Fixtures.window(WindowId.Session, hi.value, now.some)))
      Result.assert(rank(statusLo) <= rank(statusHi)).log(s"lo=$lo hi=$hi statusLo=$statusLo statusHi=$statusHi")
    }

  def testThresholds: Result = {
    def at(p: Double): AgentStatus = ofWindows(List(Fixtures.window(WindowId.Session, p, now.some)))
    Result.all(
      List(
        at(79.9d) ==== AgentStatus.Ok,
        at(80.0d) ==== AgentStatus.Warning,
        at(94.9d) ==== AgentStatus.Warning,
        at(95.0d) ==== AgentStatus.Critical,
        at(99.9d) ==== AgentStatus.Critical,
        at(100.0d) ==== AgentStatus.Exhausted,
        ofWindows(
          List(Fixtures.window(WindowId.Session, 10.0d, now.some), Fixtures.window(WindowId.Weekly, 96.0d, now.some))
        ) ==== AgentStatus.Critical,
      )
    )
  }

  def testEmpty: Result = ofWindows(Nil) ==== AgentStatus.Unavailable

  def testPerModel: Property =
    for {
      a <- Fixtures.genPercent.log("a")
      b <- Fixtures.genPercent.log("b")
    } yield {
      val fable   = WindowId.model(ModelName(NonEmptyString("Fable")))
      val opus    = WindowId.model(ModelName(NonEmptyString("Opus")))
      val windows = List(
        Fixtures.window(WindowId.Session, 10.0d, now.some),
        Fixtures.window(WindowId.Weekly, 10.0d, now.some),
        Fixtures.window(fable, a.value, now.some),
        Fixtures.window(opus, b.value, now.some),
      )
      val max     = if (a >= b) a else b
      ofWindows(windows) ==== ofWindows(List(Fixtures.window(WindowId.Session, max.value, now.some)))
    }

  def testSpendThresholds: Result = {
    def spendOf(spent: String, limit: String): AgentStatus =
      AgentStatus.of(
        Nil,
        Spend(
          Currency.iso(CurrencyCode.unsafeFrom("USD")),
          Fixtures.amount(spent),
          Fixtures.amount(limit),
          none[EpochSeconds],
        ).some,
      )
    Result.all(
      List(
        spendOf("0.05", "200.00") ==== AgentStatus.Ok,
        spendOf("160", "200") ==== AgentStatus.Warning,
        spendOf("190", "200") ==== AgentStatus.Critical,
        spendOf("200", "200") ==== AgentStatus.Exhausted,
        spendOf("0", "0") ==== AgentStatus.Exhausted,
      )
    )
  }

  def testNoWindowsNoSpend: Result = AgentStatus.of(Nil, none[Spend]) ==== AgentStatus.Unavailable

  def testWindowsAndSpend: Property =
    for {
      windows <- Fixtures.genAgentWindows.log("windows")
      spend   <- Fixtures.genSpend.option.log("spend")
    } yield {
      val percents = windows.map(_.usedPercent) ++ spend.map(_.usedPercent).toList
      val expected = percents
        .maxOption(using cats.Order[UsedPercent].toOrdering)
        .fold(AgentStatus.unavailable)(max => ofWindows(List(Fixtures.window(WindowId.Session, max.value, now.some))))
      AgentStatus.of(windows, spend) ==== expected
    }
}
