package tokenwatchroo.providers

import cats.effect.{IO, Ref, Resource}
import cats.effect.unsafe.{IORuntime, IORuntimeConfig}
import cats.syntax.all.*
import scala.concurrent.duration.*
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** Drives the real libcurl write callback (`curl_write_buffer.c`, reached through [[CurlBuffer]]) while collections
  * are forced, without libcurl.
  *
  *   - The feeder is the C function in `src/test/resources/scala-native/curl_buffer_spec_feeder.c`, reached through
  *     [[CurlBufferSpecFeeder]]. It is C and `@blocking` so that the calling thread is Unmanaged for the whole run, as
  *     it is inside `curl_easy_perform`, and no Scala memory crosses while it is. A Scala callback would allocate
  *     boxes on the Scala heap there (#41).
  *   - A fibre calls `System.gc()` in `IO.blocking` in a loop and four fibres allocate while the feeder runs, the same
  *     shape as `BridgeSpec` in the app module. The feeder pauses 1 ms after each chunk, as the recorder does, so
  *     collections land while the thread is Unmanaged inside the feed.
  *   - Waiting: the test runs on a runtime this suite owns, with a single `unsafeToFuture` and no `unsafeRunSync`, so
  *     munit's timeout applies and the parked runner thread never stalls a collection.
  *   - Linked with conditional GC yieldpoints for #44, like every binary of this build: the Scala Native 0.5.12 GC
  *     loses a root of a thread stopped by a trap-based yieldpoint and frees live objects (upstream
  *     scala-native/scala-native#5046). `BridgeSpec` keeps `Bridge.send`'s verify-and-retry as a second net, and this
  *     suite checks every byte of the assembled body.
  *   - Timeouts: the forced-collection storm runs several times slower under conditional yieldpoints on many-core
  *     machines (measured 2.2 times in mean and 4 times in the worst run on an 18-core Mac, 2026-09-20: this suite
  *     took 5.1 to 16.2 s against 3.8 to 4.9 s), while the app itself shows no cost, so `munitTimeout` is 120 s and
  *     `ProgramTimeout` 90 s.
  */
class CurlBufferSpec extends munit.FunSuite {

  override def munitTimeout: Duration = 120.seconds

  private val BodySize         = 4 * 1024 * 1024
  private val MaxChunk         = 4096
  private val PauseNanos       = 1_000_000
  private val AllocatingFibres = 4
  private val AllocationSize   = 2000

  /** The same as `AppRuntime`. */
  private val ComputeThreads = 2

  /** Shorter than `munitTimeout`, so a stuck run cancels the collection loop and the fibres before munit gives up. */
  private val ProgramTimeout = 90.seconds

  private val runtime: IORuntime = {
    val (compute, poller, shutdown) =
      IORuntime.createWorkStealingComputeThreadPool(threads = ComputeThreads, shutdownTimeout = 1.second)
    IORuntime(compute, compute, compute, List(poller), shutdown, IORuntimeConfig())
  }

  override def afterAll(): Unit = runtime.shutdown()

  /** The byte the C feeder writes at `index`. */
  private def pattern(index: Int): Byte = ((index * 31 + 7) & 0xff).toByte

  private val buffer: Resource[IO, Ptr[Byte]] =
    Resource.make(
      IO(Option(CurlBuffer.create()))
        .flatMap(_.liftTo[IO](new IllegalStateException("tw_curl_buffer_new returned NULL")))
    )(created => IO(CurlBuffer.free(created)))

  /** Counts the completed collections, as in `BridgeSpec`. Releasing the resource cancels the loop. */
  private val forcedCollections: Resource[IO, Ref[IO, Long]] =
    Resource.eval(Ref.of[IO, Long](0L)).flatMap { collections =>
      (IO.blocking(System.gc()) >> collections.update(_ + 1L)).foreverM.background.map(_ => collections)
    }

  private val allocate: IO[Unit] =
    IO(List.tabulate(AllocationSize)(_.toString).map(_.length).sum).void >> IO.cede

  private val allocating: Resource[IO, Unit] =
    List.fill(AllocatingFibres)(allocate.foreverM).parSequence_.background.void

  /** Runs only while Managed, after the feed. */
  private def assembled(buffer: Ptr[Byte]): Array[Byte] = {
    val length = CurlBuffer.length(buffer).toInt
    val bytes  = new Array[Byte](length)
    if (length > 0) {
      val _ = CurlBuffer.copy(buffer, bytes.at(0), length.toCSize)
    } else ()
    bytes
  }

  /** The first index whose byte differs from the pattern, if any. */
  private def firstMismatch(bytes: Array[Byte]): Option[Int] = {
    var index = 0
    var found = none[Int]
    while (found.isEmpty && index < bytes.length) {
      if (bytes(index) =!= pattern(index)) found = index.some else ()
      index += 1
    }
    found
  }

  test(
    "the C write callback assembles a 4 MiB body fed in many uneven chunks while a fibre forces collections and fibres allocate"
  ) {
    val resources =
      for {
        buf         <- buffer
        collections <- forcedCollections
        _           <- allocating
      } yield (buf, collections)
    val program   = resources.use {
      case (buf, collections) =>
        for {
          before   <- collections.get
          accepted <-
            IO.blocking(CurlBufferSpecFeeder.feed(buf, BodySize.toCSize, MaxChunk.toCSize, PauseNanos.toCSize).toInt)
          after    <- collections.get
          failed   <- IO(CurlBuffer.failed(buf))
          length   <- IO(CurlBuffer.length(buf).toInt)
          bytes    <- IO(assembled(buf))
          chunks   <- IO(CurlBufferSpecFeeder.chunks(BodySize.toCSize, MaxChunk.toCSize).toInt)
        } yield CurlBufferSpec.Observed(accepted, failed, length, bytes, chunks, after - before)
    }
    program
      .timeout(ProgramTimeout)
      .flatMap { observed =>
        IO {
          assertEquals(observed.failed, 0)
          assertEquals(observed.accepted, BodySize)
          assertEquals(observed.length, BodySize)
          val mismatch = firstMismatch(observed.bytes)
          assertEquals(
            mismatch,
            none[Int],
            mismatch.fold("")(index =>
              s"byte $index is ${observed.bytes(index)}, expected ${pattern(index)}, after ${observed.chunks} chunks"
            ),
          )
          assert(observed.collections > 0L, s"no collection completed while ${observed.chunks} chunks were fed")
        }
      }
      .unsafeToFuture()(using runtime)
  }

  test("an empty buffer has length 0, is not failed, and copies nothing") {
    buffer
      .use { buf =>
        IO {
          val dest = new Array[Byte](16)
          assertEquals(CurlBuffer.length(buf).toInt, 0)
          assertEquals(CurlBuffer.failed(buf), 0)
          assertEquals(CurlBuffer.copy(buf, dest.at(0), dest.length.toCSize).toInt, 0)
        }
      }
      .unsafeToFuture()(using runtime)
  }

  test("copy truncates to the destination capacity") {
    buffer
      .use { buf =>
        IO {
          val accepted = CurlBufferSpecFeeder.feed(buf, 1000.toCSize, 100.toCSize, 0.toCSize).toInt
          val dest     = new Array[Byte](100)
          val copied   = CurlBuffer.copy(buf, dest.at(0), dest.length.toCSize).toInt
          assertEquals(accepted, 1000)
          assertEquals(CurlBuffer.length(buf).toInt, 1000)
          assertEquals(copied, 100)
          assertEquals(firstMismatch(dest), none[Int])
        }
      }
      .unsafeToFuture()(using runtime)
  }
}

object CurlBufferSpec {

  /** What the test reads back after the feed: the bytes accepted and assembled, the C failure flag, the chunk count,
    * and the collections completed during the feed.
    */
  final case class Observed(
    accepted: Int,
    failed: CInt,
    length: Int,
    bytes: Array[Byte],
    chunks: Int,
    collections: Long,
  )
}
