package tokenwatchroo.core

import hedgehog.*
import hedgehog.runner.*

/** The config folder is an absolute path, kept as written (#91). */
object ClaudeConfigDirSpec extends Properties {

  override def tests: List[Test] = List(
    property("every text that starts with / is accepted unchanged", testAbsolute),
    example("an empty text and texts that do not start with / are rejected", testRejected),
  )

  def testAbsolute: Property =
    for {
      rest <- Gen
                .string(Gen.choice1(Gen.alphaNum, Gen.unicode, Gen.element1('/', '.', ' ', '~')), Range.linear(0, 30))
                .log("rest")
    } yield ClaudeConfigDir.from(s"/$rest").map(_.value) ==== Right(s"/$rest")

  def testRejected: Result =
    Result.all(
      List("", "~/x", "x/y", " /x", "./x").map(text => Result.assert(ClaudeConfigDir.from(text).isLeft).log(text))
    )
}
