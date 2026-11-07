package tokenwatchroo.core

import cats.syntax.all.*
import hedgehog.*
import hedgehog.runner.*
import refined4s.types.all.*

object AlertLevelsSpec extends Properties {

  override def tests: List[Test] = List(
    example("from accepts the defaults, no levels, five levels and a gap of 5", testAccepts),
    example("from rejects 0, 101, six levels, a gap of 4, a decrease and duplicates", testRejects),
    property("every accepted list obeys the rules", testAcceptedObeyRules),
    property("generated valid lists are accepted", testValidAccepted),
    example("crossed gives the levels with from < level <= to", testCrossed),
    example("of uses the first entry for the agent, else the defaults", testOf),
  )

  private def values(levels: AlertLevels): List[Int] = levels.levels.map(_.value)

  def testAccepts: Result =
    Result.all(
      List(
        AlertLevels.from(List(70, 85)) ==== Right(AlertLevels.defaults),
        AlertLevels.from(Nil) ==== Right(AlertLevels.none),
        AlertLevels.from(List(1, 6, 11, 16, 100)).map(values) ==== Right(List(1, 6, 11, 16, 100)),
        AlertLevels.from(List(50, 55)).map(values) ==== Right(List(50, 55)),
        values(AlertLevels.defaults) ==== List(70, 85),
      )
    )

  def testRejects: Result =
    Result.all(
      List(
        List(0),
        List(101),
        List(10, 20, 30, 40, 50, 60),
        List(70, 74),
        List(85, 70),
        List(70, 70),
      ).map(levels => Result.assert(AlertLevels.from(levels).isLeft).log(levels.show))
    )

  def testAcceptedObeyRules: Property =
    for {
      levels <- Gen.int(Range.linear(-5, 110)).list(Range.linear(0, 7)).log("levels")
    } yield AlertLevels.from(levels) match {
      case Right(accepted) =>
        val vs = values(accepted)
        Result.all(
          List(
            Result.assert(vs.length <= AlertLevels.MaxCount),
            Result.assert(vs.forall(v => v >= 1 && v <= 100)),
            Result.assert(vs.zip(vs.drop(1)).forall { case (a, b) => b - a >= AlertLevels.MinGap }),
            vs ==== levels,
          )
        )
      case Left(_) => Result.success
    }

  def testValidAccepted: Property =
    for {
      levels <- Fixtures.genAlertLevelValues.log("levels")
    } yield AlertLevels.from(levels).map(values) ==== Right(levels)

  def testCrossed: Result = {
    def crossed(levels: AlertLevels, from: Long, to: Long): List[Int] = levels.crossed(from, to).map(_.value)
    Result.all(
      List(
        crossed(AlertLevels.defaults, 0L, 85L) ==== List(70, 85),
        crossed(AlertLevels.defaults, 0L, 69L) ==== Nil,
        crossed(AlertLevels.defaults, 69L, 70L) ==== List(70),
        crossed(AlertLevels.defaults, 70L, 70L) ==== Nil,
        crossed(AlertLevels.defaults, 70L, 84L) ==== Nil,
        crossed(AlertLevels.defaults, 84L, 100L) ==== List(85),
        crossed(AlertLevels.none, 0L, 100L) ==== Nil,
      )
    )
  }

  def testOf: Result = {
    val sixty  = AlertLevels.from(List(60)).getOrElse(AlertLevels.defaults)
    val first  = AgentConfig.defaults(AgentId.Codex).copy(alertLevels = sixty)
    val second = AgentConfig.defaults(AgentId.Codex).copy(alertLevels = AlertLevels.none)
    val config = Config.default(StateDir(NonEmptyString("/tmp/tw"))).copy(agents = List(first, second))
    Result.all(
      List(
        AlertLevels.of(config, AgentId.Codex) ==== sixty,
        AlertLevels.of(config, AgentId.ClaudeCode) ==== AlertLevels.defaults,
      )
    )
  }
}
