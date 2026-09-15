package tokenwatchroo.app

import cats.effect.{IO, Ref}
import java.nio.charset.StandardCharsets
import scala.annotation.tailrec
import scala.scalanative.libc.string
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import tokenwatchroo.core.*
import tokenwatchroo.core.codecs.given

/** Where envelopes go. The poller only knows this trait, so tests can record envelopes in memory. */
trait EnvelopeSink {
  def emit(build: SequenceNumber => Envelope): IO[Unit]
}

/** Delivers envelopes to the Swift callback. The JSON lives in a `Zone` for the duration of the call, never in the
  * garbage-collected heap, and the calling worker thread is switched Unmanaged around the call so a collection never
  * has to wait for Swift code. Every conversion is verified against the JSON bytes before the switch (#44).
  */
final class Bridge(
  callback: CFuncPtr2[CString, Ptr[Byte], Unit],
  ctx: Ptr[Byte],
  seq: Ref[IO, SequenceNumber],
  corruptions: Ref[IO, Long],
) extends EnvelopeSink {

  /** How many conversions were found corrupted and redone (#44). */
  val corrupted: IO[Long] = corruptions.get

  override def emit(build: SequenceNumber => Envelope): IO[Unit] =
    for {
      next <- seq.updateAndGet(_.next)
      json = codecs.write(build(next))
      mismatches <- IO.blocking(send(next, json))
      _          <- corruptions.update(_ + mismatches.toLong)
    } yield ()

  private def send(seq: SequenceNumber, json: String): Int =
    Zone {
      val converted = Bridge.convert(json)
      if (converted.mismatches > 0) {
        System
          .err
          .println(
            s"[token-watchroo] envelope ${seq.value}: the C string differed from the JSON ${converted.mismatches} time(s) and was converted again (#44)"
          )
      }
      GcState.set(MutatorState.Unmanaged)
      callback(converted.cString, ctx)
      GcState.yieldNow()
      GcState.set(MutatorState.Managed)
      converted.mismatches
    }
}

object Bridge {

  /** A verified C string and how many conversions were rejected before it. */
  final case class Converted(cString: CString, mismatches: Int)

  val MaxAttempts: Int = 3

  def make(callback: CFuncPtr2[CString, Ptr[Byte], Unit], ctx: Ptr[Byte]): IO[Bridge] =
    for {
      seq         <- Ref.of[IO, SequenceNumber](SequenceNumber(0L))
      corruptions <- Ref.of[IO, Long](0L)
    } yield new Bridge(callback, ctx, seq, corruptions)

  /** Converts the JSON with `toCString` and checks the result against the JSON's own UTF-8 bytes while Managed.
    *
    * The Scala Native 0.5.12 GC can sweep the line of the encoder's live output array as free (#44), which leaves the
    * C string with line metadata or a new object's header in it. The JSON `String` was never seen corrupted, so its
    * bytes are the reference: a mismatch means the conversion is redone, and after [[MaxAttempts]] the reference bytes
    * are copied directly.
    */
  def convert(json: String)(using zone: Zone): Converted = {
    @tailrec
    def attempt(n: Int): Converted = {
      val expected = json.getBytes(StandardCharsets.UTF_8)
      val cString  = toCString(json)
      if (matches(cString, expected)) Converted(cString, n)
      else if (n + 1 < MaxAttempts) attempt(n + 1)
      else Converted(copy(expected), MaxAttempts)
    }
    attempt(0)
  }

  private def matches(cString: CString, expected: Array[Byte]): Boolean = {
    var index = 0
    var same  = true
    while (same && index < expected.length) {
      same = cString(index) == expected(index)
      index += 1
    }
    same && cString(expected.length) == 0
  }

  private def copy(bytes: Array[Byte])(using zone: Zone): CString = {
    val cString = zone.alloc((bytes.length + 1).toCSize)
    val _       = string.memcpy(cString, bytes.at(0), bytes.length.toCSize)
    cString(bytes.length) = 0.toByte
    cString
  }
}
