package tokenwatchroo.app

import cats.effect.{IO, Ref, Resource}
import cats.effect.unsafe.{IORuntime, IORuntimeConfig}
import cats.syntax.all.*
import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import tokenwatchroo.core.*
import tokenwatchroo.core.codecs.given

/** Drives a real `Bridge` with a C callback while collections are forced, the path the app takes for every envelope.
  *
  *   - The callback is the C recorder in `src/test/resources/scala-native/bridge_spec_recorder.c`, reached through
  *     [[BridgeSpecRecorder]]. `Bridge.send` calls it while the worker is Unmanaged, where a Scala callback would
  *     allocate boxes on the Scala heap (#41). The recorder copies each envelope into malloc memory reached through
  *     `ctx` and pauses for 1 ms, so collections land while callbacks run.
  *   - A fibre calls `System.gc()` in `IO.blocking` in a loop and four fibres allocate while one fibre emits. A single
  *     emitter keeps the `seq` order meaningful, as in the app. `System.gc()` on Scala Native runs a commix collection
  *     through `scalanative_GC_collect`, and it is the only way to force collections at chosen moments. The app never
  *     calls it. Its collections come from allocation, which the allocating fibres also cause.
  *   - The recorder also keeps the FNV-1a 64 hash of each envelope as it arrived. A hash that differs from the expected
  *     string's hash means the envelope was corrupted before or during the callback. Equal hashes with different lines
  *     mean it was corrupted after recording.
  *   - Waiting: the test runs on a runtime this suite owns, built like `AppRuntime.start`, with a single
  *     `unsafeToFuture` and no `unsafeRunSync`. The body returns the `Future` at once, so munit's timeout applies. The
  *     runner thread waits in `Await.result`, which parks through `LockSupport.park` in a `@blocking` pthread call, and a
  *     parked thread is Unmanaged, so the wait never stalls a collection.
  *   - `Bridge.send` verifies every conversion against the JSON bytes and redoes it on a mismatch (#44); the suite
  *     reads the count of redone conversions and shows it in a failure clue. It stays as a second net: the library is
  *     linked with conditional GC yieldpoints for #44, because the Scala Native 0.5.12 GC loses a root of a thread
  *     stopped by a trap-based yieldpoint and frees live objects (upstream scala-native/scala-native#5046).
  *   - Timeouts: the forced-collection storm runs several times slower under conditional yieldpoints on many-core
  *     machines (measured 2.2 times in mean and 4 times in the worst run on an 18-core Mac, 2026-09-20, where this
  *     suite timed out at 30 s in both modes), while the app itself shows no cost, so `munitTimeout` is 120 s and
  *     `ProgramTimeout` 90 s.
  */
class BridgeSpec extends munit.FunSuite {

  override def munitTimeout: Duration = 120.seconds

  private val EnvelopeCount    = 200
  private val AllocatingFibres = 4
  private val AllocationSize   = 2000

  private val FnvOffsetBasis = 0xcbf29ce484222325L
  private val FnvPrime       = 0x100000001b3L

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

  private val now      = EpochSeconds(1789185600L)
  private val resetsAt = EpochSeconds(1789189920L)

  private def snapshotAt(index: Int): Snapshot =
    Snapshot.of(
      EpochSeconds(now.value + index.toLong),
      List(
        AgentSnapshot.available(
          AgentId.ClaudeCode,
          none[PlanLabel],
          UsageMeters(
            List(UsageWindow.clamped(WindowId.Session, (index % 100).toDouble, resetsAt.some, Seconds(18000L).some)),
            none[Spend],
          ),
          Source.Api,
          now,
          none[ErrorMessage],
        )
      ),
    )

  private def alertAt(index: Int): Alert =
    Alert(
      AgentId.Codex,
      WindowId.Session,
      AlertKind.Warning80,
      resetsAt,
      UsedPercent.clamp(80.0d + (index % 20).toDouble),
      s"Codex is near its session limit ($index)",
      "82% of the 5 h window used. Resets in 24 minutes.",
    )

  private def buildAt(index: Int): SequenceNumber => Envelope =
    index % 3 match {
      case 0 => seq => Envelope.snapshot(seq, snapshotAt(index))
      case 1 => seq => Envelope.alert(seq, alertAt(index))
      case _ => seq => Envelope.error(seq, s"error $index with \"quotes\", a tab\tand ünïcödé")
    }

  /** FNV-1a 64 over the UTF-8 bytes, the same hash the C recorder computes. */
  private def fnv1a(text: String): Long =
    text
      .getBytes(StandardCharsets.UTF_8)
      .foldLeft(FnvOffsetBasis)((hash, byte) => (hash ^ (byte & 0xff).toLong) * FnvPrime)

  private val recorder: Resource[IO, Ptr[Byte]] =
    Resource.make(
      IO(Option(BridgeSpecRecorder.create()))
        .flatMap(_.liftTo[IO](new IllegalStateException("tw_test_recorder_new returned NULL")))
    )(created => IO(BridgeSpecRecorder.free(created)))

  /** Counts the completed collections. Releasing the resource cancels the loop between collections. */
  private val forcedCollections: Resource[IO, Ref[IO, Long]] =
    Resource.eval(Ref.of[IO, Long](0L)).flatMap { collections =>
      (IO.blocking(System.gc()) >> collections.update(_ + 1L)).foreverM.background.map(_ => collections)
    }

  private val allocate: IO[Unit] =
    IO(List.tabulate(AllocationSize)(_.toString).map(_.length).sum).void >> IO.cede

  private val allocating: Resource[IO, Unit] =
    List.fill(AllocatingFibres)(allocate.foreverM).parSequence_.background.void

  /** Runs only while Managed, after the emits have finished. */
  private def recorded(recorder: Ptr[Byte]): List[String] = {
    val length = BridgeSpecRecorder.length(recorder).toInt
    val bytes  = new Array[Byte](length)
    val copied = if (length > 0) BridgeSpecRecorder.copy(recorder, bytes.at(0), length.toCSize).toInt else 0
    Option
      .when(copied > 0)(new String(bytes, 0, copied, StandardCharsets.UTF_8))
      .fold(List.empty[String])(_.split('\n').toList)
  }

  /** Runs only while Managed, after the emits have finished. */
  private def recordedHashes(
    recorder: Ptr[Byte],
    count: Int,
    copy: (Ptr[Byte], Ptr[Long], CSize) => CSize,
  ): List[Long] = {
    val hashes = new Array[Long](count)
    val copied = if (count > 0) copy(recorder, hashes.at(0), count.toCSize).toInt else 0
    hashes.take(copied).toList
  }

  test(
    "every envelope reaches the C callback in seq order while a fibre forces collections and fibres allocate"
  ) {
    val builds         = List.tabulate(EnvelopeCount)(buildAt)
    val expected       = builds.zipWithIndex.map {
      case (build, index) => codecs.write(build(SequenceNumber(index.toLong + 1L)))
    }
    val expectedHashes = expected.map(fnv1a)
    val resources      =
      for {
        recorderPtr <- recorder
        collections <- forcedCollections
        _           <- allocating
      } yield (recorderPtr, collections)
    val program        = resources.use {
      case (recorderPtr, collections) =>
        for {
          bridge <- Bridge.make(BridgeSpecRecorder.callback(), recorderPtr)
          before <- collections.get
          _      <- builds.traverse_(bridge.emit)
          after  <- collections.get
          redone <- bridge.corrupted
          count  <- IO(BridgeSpecRecorder.count(recorderPtr).toInt)
          failed <- IO(BridgeSpecRecorder.failed(recorderPtr))
          hashes <- IO(recordedHashes(recorderPtr, count, BridgeSpecRecorder.copyHashes))
          exits  <- IO(recordedHashes(recorderPtr, count, BridgeSpecRecorder.copyExitHashes))
          lines  <- IO(recorded(recorderPtr))
        } yield BridgeSpec.Observed(count, failed, hashes, exits, lines, redone, after - before)
    }
    program
      .timeout(ProgramTimeout)
      .flatMap { observed =>
        IO {
          assertEquals(observed.failed, 0)
          assertEquals(observed.count, EnvelopeCount)
          assertEquals(observed.hashes.size, EnvelopeCount)
          assertEquals(observed.exitHashes.size, EnvelopeCount)
          val corruptedOnArrival    = observed.hashes.zip(expectedHashes).zipWithIndex.collect {
            case ((arrived, wanted), index) if arrived =!= wanted => index + 1
          }
          val changedDuringCallback = observed.hashes.zip(observed.exitHashes).zipWithIndex.collect {
            case ((arrived, ended), index) if arrived =!= ended => index + 1
          }
          assertEquals(
            (corruptedOnArrival ++ changedDuringCallback).distinct.sorted,
            List.empty[Int],
            BridgeSpec.corruptionClue(
              observed.redone,
              corruptedOnArrival,
              changedDuringCallback,
              observed.lines,
              expected,
            ),
          )
          assertEquals(observed.lines, expected, "corrupted after recording")
          assert(observed.collections > 0L, s"no collection completed while $EnvelopeCount envelopes were emitted")
        }
      }
      .unsafeToFuture()(using runtime)
  }
}

object BridgeSpec {

  /** What the test reads back after the emits: the recorder state, the conversions `Bridge` redone (#44), and the
    * collections completed during the emits.
    */
  final case class Observed(
    count: Int,
    failed: CInt,
    hashes: List[Long],
    exitHashes: List[Long],
    lines: List[String],
    redone: Long,
    collections: Long,
  )

  /** Names each envelope whose hash was wrong on arrival or changed while the callback ran, with the bytes the
    * recorder holds for it and the expected JSON, so a failure shows what changed and when.
    */
  // TODO: REVIEWME: It should be reviewed by Kevin.
  def corruptionClue(
    redone: Long,
    corruptedOnArrival: List[Int],
    changedDuringCallback: List[Int],
    recorded: List[String],
    expected: List[String],
  ): String =
    (corruptedOnArrival ++ changedDuringCallback)
      .distinct
      .sorted
      .map { seq =>
        val index   = seq - 1
        val arrival = if (corruptedOnArrival.contains(seq)) "wrong on arrival" else "right on arrival"
        val during  =
          if (changedDuringCallback.contains(seq)) "changed while the callback ran"
          else "unchanged while the callback ran"
        s"seq $seq $arrival, $during, recorded ${recorded.lift(index).getOrElse("<missing>")} expected ${expected
            .lift(index)
            .getOrElse("<missing>")}"
      }
      .mkString(s"conversions redone by Bridge: $redone; corrupted envelopes: ", "; ", "")
}
