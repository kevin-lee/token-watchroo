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
  )

  private val now = EpochSeconds(1789185600L)

  private def rank(status: AgentStatus): Int = status match {
    case AgentStatus.Unavailable => -1
    case AgentStatus.Ok => 0
    case AgentStatus.Warning => 1
    case AgentStatus.Critical => 2
    case AgentStatus.Exhausted => 3
  }

  def testMonotonic: Property =
    for {
      a <- Fixtures.genPercent.log("a")
      b <- Fixtures.genPercent.log("b")
    } yield {
      val (lo, hi) = if (a <= b) (a, b) else (b, a)
      val statusLo = AgentStatus.of(List(Fixtures.window(WindowId.Session, lo.value, now.some)))
      val statusHi = AgentStatus.of(List(Fixtures.window(WindowId.Session, hi.value, now.some)))
      Result.assert(rank(statusLo) <= rank(statusHi)).log(s"lo=$lo hi=$hi statusLo=$statusLo statusHi=$statusHi")
    }

  def testThresholds: Result = {
    def at(p: Double): AgentStatus = AgentStatus.of(List(Fixtures.window(WindowId.Session, p, now.some)))
    Result.all(
      List(
        at(79.9d) ==== AgentStatus.Ok,
        at(80.0d) ==== AgentStatus.Warning,
        at(94.9d) ==== AgentStatus.Warning,
        at(95.0d) ==== AgentStatus.Critical,
        at(99.9d) ==== AgentStatus.Critical,
        at(100.0d) ==== AgentStatus.Exhausted,
        AgentStatus.of(
          List(Fixtures.window(WindowId.Session, 10.0d, now.some), Fixtures.window(WindowId.Weekly, 96.0d, now.some))
        ) ==== AgentStatus.Critical,
      )
    )
  }

  def testEmpty: Result = AgentStatus.of(Nil) ==== AgentStatus.Unavailable

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
      AgentStatus.of(windows) ==== AgentStatus.of(List(Fixtures.window(WindowId.Session, max.value, now.some)))
    }
}
