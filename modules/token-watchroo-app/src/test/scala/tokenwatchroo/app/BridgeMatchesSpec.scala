package tokenwatchroo.app

import cats.syntax.all.*
import hedgehog.*
import hedgehog.runner.*
import java.nio.charset.StandardCharsets
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** `Bridge.matches` against the `while` loop it replaced and against a model on lists.
  *
  * `BridgeSpec` reaches `matches` only through `emit`, where `toCString` is correct unless the GC corrupts memory (#44),
  * so it only sees C strings that match. These properties also build C strings that differ on purpose.
  */
object BridgeMatchesSpec extends Properties {

  override def tests: List[Test] = List(
    property("matches gives the same result as the old while loop and the model", testSameAsOldAndModel),
    property("toCString of any string matches the string's UTF-8 bytes", testToCString),
    property("a C string with one changed byte does not match", testChangedByte),
    property("a C string with no NUL at the expected length does not match", testNoNulAtEnd),
    property("a C string that ends before the expected bytes does not match", testEndsEarly),
    example("an empty C string matches no expected bytes", testEmpty),
  )

  /** The bytes written to the C string before its NUL, and the bytes `matches` expects. */
  final case class Input(expected: List[Byte], content: List[Byte])

  private val genByte: Gen[Byte] = Gen.byte(Range.constant(Byte.MinValue, Byte.MaxValue))

  private val genNonNulByte: Gen[Byte] =
    Gen.choice1(Gen.byte(Range.constant(1.toByte, Byte.MaxValue)), Gen.byte(Range.constant(Byte.MinValue, -1.toByte)))

  private val genBytes: Gen[List[Byte]] = genByte.list(Range.linear(0, 64))

  private def genChanged(bytes: List[Byte]): Gen[List[Byte]] =
    if (bytes.isEmpty) Gen.constant(bytes)
    else
      for {
        index <- Gen.int(Range.constant(0, bytes.length - 1))
        delta <- Gen.int(Range.constant(1, 255))
      } yield bytes.updated(index, (bytes(index) + delta).toByte)

  /** The same bytes, one changed byte, extra bytes, a prefix, or any bytes. */
  private val genInput: Gen[Input] =
    for {
      expected <- genBytes
      content  <- Gen.choice1(
                    Gen.constant(expected),
                    genChanged(expected),
                    genByte.list(Range.linear(1, 8)).map(extra => expected ++ extra),
                    Gen.int(Range.linear(0, expected.length)).map(expected.take),
                    genBytes,
                  )
    } yield Input(expected, content)

  /** Writes `content` and then NUL bytes up to `size`, so that every index `matches` reads is inside the buffer. */
  private def withCString[A](content: List[Byte], size: Int)(f: CString => A): A =
    Zone {
      val cString = summon[Zone].alloc(size.toCSize)
      val padded  = content.padTo(size, 0.toByte)
      padded.zipWithIndex.foreach { case (byte, index) => cString(index) = byte }
      f(cString)
    }

  private def bufferSize(input: Input): Int = math.max(input.content.length, input.expected.length) + 1

  /** A copy of `Bridge.matches` before it was rewritten without `var`, kept as the reference. The `var`s, `while` and
    * `==` stay on purpose: the property compares the new code with the exact old code.
    */
  private def oldMatches(cString: CString, expected: Array[Byte]): Boolean = {
    var index = 0
    var same  = true
    while (same && index < expected.length) {
      same = cString(index) == expected(index)
      index += 1
    }
    same && cString(expected.length) == 0
  }

  /** The first `expected.length` bytes are the expected bytes and the byte after them is NUL. */
  private def model(input: Input): Boolean = {
    val padded = input.content.padTo(input.expected.length + 1, 0.toByte)
    padded.take(input.expected.length) === input.expected && padded(input.expected.length) === 0.toByte
  }

  def testSameAsOldAndModel: Property =
    genInput.log("input").map { input =>
      val expected = input.expected.toArray
      withCString(input.content, bufferSize(input)) { cString =>
        val actual = Bridge.matches(cString, expected)
        Result.all(
          List(
            (actual ==== oldMatches(cString, expected)).log("against the old while loop"),
            (actual ==== model(input)).log("against the model"),
          )
        )
      }
    }

  def testToCString: Property =
    Gen.string(Gen.unicode, Range.linear(0, 64)).log("json").map { json =>
      Zone {
        Result.assert(Bridge.matches(toCString(json), json.getBytes(StandardCharsets.UTF_8)))
      }
    }

  def testChangedByte: Property =
    for {
      expected <- genByte.list(Range.linear(1, 64)).log("expected")
      content  <- genChanged(expected).log("content")
    } yield {
      val input = Input(expected, content)
      withCString(content, bufferSize(input))(cString =>
        Result.diff(Bridge.matches(cString, expected.toArray), false)(_ === _)
      )
    }

  def testNoNulAtEnd: Property =
    for {
      expected <- genBytes.log("expected")
      first    <- genNonNulByte.log("first extra byte")
      rest     <- genByte.list(Range.linear(0, 8)).log("other extra bytes")
    } yield {
      val input = Input(expected, expected ++ (first :: rest))
      withCString(input.content, bufferSize(input))(cString =>
        Result.diff(Bridge.matches(cString, expected.toArray), false)(_ === _)
      )
    }

  def testEndsEarly: Property =
    for {
      expected <- genNonNulByte.list(Range.linear(1, 64)).log("expected")
      length   <- Gen.int(Range.linear(0, expected.length - 1)).log("content length")
    } yield {
      val input = Input(expected, expected.take(length))
      withCString(input.content, bufferSize(input))(cString =>
        Result.diff(Bridge.matches(cString, expected.toArray), false)(_ === _)
      )
    }

  def testEmpty: Result =
    withCString(List.empty[Byte], 1)(cString => Result.assert(Bridge.matches(cString, Array.empty[Byte])))
}
