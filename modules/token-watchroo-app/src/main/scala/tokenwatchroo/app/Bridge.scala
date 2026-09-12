package tokenwatchroo.app

import cats.effect.{IO, Ref}
import scala.scalanative.unsafe.*
import tokenwatchroo.core.*
import tokenwatchroo.core.codecs.given

/** Where envelopes go. The poller only knows this trait, so tests can record envelopes in memory. */
trait EnvelopeSink {
  def emit(build: SequenceNumber => Envelope): IO[Unit]
}

/** Delivers envelopes to the Swift callback. The JSON lives in a `Zone` for the duration of the call, never in the
  * garbage-collected heap, and the calling worker thread is switched Unmanaged around the call so a collection never
  * has to wait for Swift code.
  */
final class Bridge(callback: CFuncPtr2[CString, Ptr[Byte], Unit], ctx: Ptr[Byte], seq: Ref[IO, SequenceNumber])
    extends EnvelopeSink {

  override def emit(build: SequenceNumber => Envelope): IO[Unit] =
    for {
      next <- seq.updateAndGet(_.next)
      json = codecs.write(build(next))
      _ <- IO.blocking(send(json))
    } yield ()

  private def send(json: String): Unit =
    Zone {
      val cString = toCString(json)
      GcState.set(MutatorState.Unmanaged)
      callback(cString, ctx)
      GcState.yieldNow()
      GcState.set(MutatorState.Managed)
    }
}

object Bridge {
  def make(callback: CFuncPtr2[CString, Ptr[Byte], Unit], ctx: Ptr[Byte]): IO[Bridge] =
    Ref.of[IO, SequenceNumber](SequenceNumber(0L)).map(seq => new Bridge(callback, ctx, seq))
}
