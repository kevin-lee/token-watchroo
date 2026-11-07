package tokenwatchroo.providers

import cats.syntax.all.*
import hedgehog.*
import hedgehog.runner.*
import tokenwatchroo.providers.CurlBufferSpec.{firstMismatch, pattern}

/** `CurlBufferSpec.firstMismatch`, which the storm test only reaches with a body that has no mismatch. */
object CurlBufferPatternSpec extends Properties {

  override def tests: List[Test] = List(
    property("firstMismatch gives the same result as the old while loop", testSameAsOld),
    property("bytes that follow the pattern have no mismatch", testNoMismatch),
    property("one changed byte is the first mismatch", testOneChanged),
    property("of two changed bytes, the one with the lower index is the first mismatch", testTwoChanged),
  )

  /** A byte at `index` changed by `delta`, which is never a multiple of 256, so the byte is always different. */
  final case class Change(index: Int, delta: Int)

  private val MaxLength = 512

  private def patternBytes(length: Int): List[Byte] = List.tabulate(length)(pattern)

  private def changed(bytes: List[Byte], change: Change): List[Byte] =
    bytes.updated(change.index, (bytes(change.index) + change.delta).toByte)

  private def genChange(length: Int): Gen[Change] =
    for {
      index <- Gen.int(Range.constant(0, length - 1))
      delta <- Gen.int(Range.constant(1, 255))
    } yield Change(index, delta)

  /** Pattern bytes with no change, one change or more changes. */
  private val genBody: Gen[List[Byte]] =
    for {
      length  <- Gen.int(Range.linear(0, MaxLength))
      changes <- if (length > 0) genChange(length).list(Range.linear(0, 3)) else Gen.constant(List.empty[Change])
    } yield changes.foldLeft(patternBytes(length))(changed)

  /** A copy of `firstMismatch` before it was rewritten without `var`, kept as the reference. The `var`s and `while`
    * stay on purpose: the property compares the new code with the exact old code.
    */
  private def oldFirstMismatch(bytes: Array[Byte]): Option[Int] = {
    var index = 0
    var found = none[Int]
    while (found.isEmpty && index < bytes.length) {
      if (bytes(index) =!= pattern(index)) found = index.some else ()
      index += 1
    }
    found
  }

  def testSameAsOld: Property =
    genBody.log("body").map { body =>
      firstMismatch(body.toArray) ==== oldFirstMismatch(body.toArray)
    }

  def testNoMismatch: Property =
    Gen.int(Range.linear(0, MaxLength)).log("length").map { length =>
      firstMismatch(patternBytes(length).toArray) ==== None
    }

  def testOneChanged: Property =
    for {
      length <- Gen.int(Range.linear(1, MaxLength)).log("length")
      change <- genChange(length).log("change")
    } yield firstMismatch(changed(patternBytes(length), change).toArray) ==== Some(change.index)

  def testTwoChanged: Property =
    for {
      length <- Gen.int(Range.linear(1, MaxLength)).log("length")
      first  <- genChange(length).log("first change")
      second <- genChange(length).log("second change")
    } yield {
      val body = changed(changed(patternBytes(length), first), second)
      firstMismatch(body.toArray) ==== Some(math.min(first.index, second.index))
    }
}
