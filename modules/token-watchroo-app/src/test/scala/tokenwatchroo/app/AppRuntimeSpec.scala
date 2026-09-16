package tokenwatchroo.app

import cats.effect.IO
import cats.effect.unsafe.{IORuntime, IORuntimeConfig}
import cats.syntax.all.*
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import refined4s.types.all.*
import scala.concurrent.Future
import scala.concurrent.duration.*
import tokenwatchroo.core.*
import tokenwatchroo.providers.UsageProvider

/** Drives `AppRuntime.run`, `submit`, and `shutdown` the way the exports do, from the munit thread, which stands in
  * for the host's main thread. The program runs on a runtime this suite owns, built like `AppRuntime.start`, entered
  * once with `unsafeToFuture`; the body returns that `Future`, so munit's timeout applies and its completion is the
  * proof that the loop ended. The sink and the provider record into Java atomics so the test thread reads them without
  * another `unsafeRun*`. `shutdown` gets a thunk that only records that it ran, because the suite's runtime is shut
  * down in `afterAll`.
  */
class AppRuntimeSpec extends munit.FunSuite {

  override def munitTimeout: Duration = 30.seconds

  private val ComputeThreads = 2

  private val runtime: IORuntime = {
    val (compute, poller, shutdown) =
      IORuntime.createWorkStealingComputeThreadPool(threads = ComputeThreads, shutdownTimeout = 1.second)
    IORuntime(compute, compute, compute, List(poller), shutdown, IORuntimeConfig())
  }

  override def afterAll(): Unit = runtime.shutdown()

  private val now      = EpochSeconds(1789185600L)
  private val resetsAt = EpochSeconds(1789189920L)
  private val config   =
    Config(
      StateDir(NonEmptyString("/tmp/token-watchroo-app-tests")),
      RefreshIntervalSeconds.clamp(15),
      none[CodexHome],
      none[ClaudeCodeVersion],
    )

  private def window(percent: Double): UsageWindow =
    UsageWindow.clamped(WindowId.Session, percent, resetsAt.some, Seconds(18000L).some)

  private def claudeSnapshot(percent: Double, at: EpochSeconds): AgentSnapshot =
    AgentSnapshot
      .available(
        AgentId.ClaudeCode,
        none[PlanLabel],
        UsageMeters(List(window(percent)), none[Spend]),
        Source.Api,
        at,
        none[ErrorMessage],
      )

  /** Records the trigger of every fetch into a Java atomic. */
  final private class Stub(triggers: AtomicReference[List[FetchTrigger]]) extends UsageProvider {
    override def id: AgentId                           = AgentId.ClaudeCode
    override def detect(config: Config): IO[Detection] = IO.pure(Detection.Detected)
    override def fetch(now: EpochSeconds, config: Config, trigger: FetchTrigger): IO[AgentSnapshot] =
      IO.delay(triggers.updateAndGet(_ :+ trigger)).as(claudeSnapshot(10.0d, now))
  }

  /** Records every envelope into a Java atomic. */
  final private class AtomicSink(envelopes: AtomicReference[List[Envelope]]) extends EnvelopeSink {
    override def emit(build: SequenceNumber => Envelope): IO[Unit] =
      IO.delay(envelopes.updateAndGet(all => all :+ build(SequenceNumber(all.size.toLong + 1L)))).void
  }

  private def snapshots(envelopes: List[Envelope]): List[Snapshot] =
    envelopes.collect { case Envelope.Snapshot(_, data) => data }

  /** Polls from the test thread. `Thread.sleep` parks in a `@blocking` call, so the thread is Unmanaged while it waits.
    */
  private def awaitOn(deadline: FiniteDuration, what: String)(ready: => Boolean): Unit = {
    val end = System.nanoTime() + deadline.toNanos
    while (!ready && System.nanoTime() < end) Thread.sleep(20L)
    assert(ready, s"timed out waiting for $what")
  }

  private def isRunning(state: AtomicReference[Lifecycle]): Boolean =
    state.get() match {
      case Lifecycle.Running(_, _) => true
      case Lifecycle.Idle | Lifecycle.Starting(_) => false
    }

  private def startProgram(
    state: AtomicReference[Lifecycle],
    triggers: AtomicReference[List[FetchTrigger]],
    envelopes: AtomicReference[List[Envelope]],
  ): Future[Unit] =
    InMemoryStateStore
      .make
      .flatMap { store =>
        AppRuntime.run(state, config, List(new Stub(triggers)), new AtomicSink(envelopes), store, IO.pure(now))
      }
      .unsafeToFuture()(using runtime)

  test("the program publishes its handles, refresh and shutdown go through the dispatcher, and the loop ends") {
    val stopped   = new AtomicBoolean(false)
    val state     = new AtomicReference[Lifecycle](Lifecycle.Starting(() => stopped.set(true)))
    val triggers  = new AtomicReference[List[FetchTrigger]](Nil)
    val envelopes = new AtomicReference[List[Envelope]](Nil)

    val future = startProgram(state, triggers, envelopes)

    awaitOn(5.seconds, "publication")(isRunning(state))
    awaitOn(5.seconds, "the first snapshot")(snapshots(envelopes.get()).size >= 1)
    assertEquals(AppRuntime.submit(state, Command.refresh), Entry.Ok)
    awaitOn(3.seconds, "the second snapshot")(snapshots(envelopes.get()).size >= 2)
    assertEquals(AppRuntime.shutdown(state), Entry.Ok)
    assert(stopped.get())
    assertEquals(state.get(), Lifecycle.Idle)
    assertEquals(AppRuntime.submit(state, Command.refresh), Entry.Internal)

    future.map { _ =>
      assertEquals(snapshots(envelopes.get()).size, 2)
      assertEquals(triggers.get(), List(FetchTrigger.Scheduled, FetchTrigger.Manual))
    }(using munitExecutionContext)
  }

  test("an export before publication returns Internal and a shutdown in that window still stops the runtime") {
    val stopped = new AtomicBoolean(false)
    val state   = new AtomicReference[Lifecycle](Lifecycle.Starting(() => stopped.set(true)))

    assertEquals(AppRuntime.submit(state, Command.refresh), Entry.Internal)
    assertEquals(AppRuntime.shutdown(state), Entry.Ok)
    assert(stopped.get())
    assertEquals(state.get(), Lifecycle.Idle)
    assertEquals(AppRuntime.shutdown(state), Entry.Ok)
  }

  test("a shutdown before publication keeps the poller from starting") {
    val state     = new AtomicReference[Lifecycle](Lifecycle.Starting(() => ()))
    val triggers  = new AtomicReference[List[FetchTrigger]](Nil)
    val envelopes = new AtomicReference[List[Envelope]](Nil)

    assertEquals(AppRuntime.shutdown(state), Entry.Ok)
    val future = startProgram(state, triggers, envelopes)

    future.map { _ =>
      assertEquals(envelopes.get(), Nil)
      assertEquals(state.get(), Lifecycle.Idle)
    }(using munitExecutionContext)
  }
}
