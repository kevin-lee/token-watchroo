package tokenwatchroo.app

import cats.effect.{IO, Resource}
import cats.effect.std.{Dispatcher, Queue}
import cats.effect.unsafe.{IORuntime, IORuntimeConfig}
import cats.syntax.all.*
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*
import scala.scalanative.unsafe.*
import tokenwatchroo.core.*
import tokenwatchroo.core.codecs.given
import tokenwatchroo.providers.{CurlHttp, Env, Providers, UsageProvider}

/** What the program publishes once its resources exist: the sequential dispatcher the exports submit through and the
  * command queue the poller reads.
  */
final class Handles(val dispatcher: Dispatcher[IO], val queue: Queue[IO, Command])

/** What the boundary knows about the library. `shutdownRuntime` is the thunk returned with the compute pool, kept here
  * so `tw_shutdown` can stop the pool in any state after `tw_start`.
  */
enum Lifecycle derives CanEqual {
  case Idle

  /** The runtime exists and the program is running but has not published its handles yet. */
  case Starting(shutdownRuntime: () => Unit)
  case Running(handles: Handles, shutdownRuntime: () => Unit)
}

/** What the exported entry points do. Every method runs inside the managed region of `Entry.managed` and returns a
  * return code. The runtime is entered exactly once, in `start`, with `unsafeRunAndForget` for the program. The program
  * creates a `Dispatcher.sequential[IO]` and the command queue as resources and publishes them in
  * [[Lifecycle.Running]]; the exports submit `queue.offer(command)` through that dispatcher, whose submit path is a
  * lock-free enqueue plus an unpark of a worker and never parks the calling thread (cats-effect 3.7.1,
  * `Dispatcher.unsafeRunAndForget` and `WorkStealingThreadPool.scheduleExternal`, #42). None of them ever blocks on a
  * fibre.
  */
object AppRuntime {

  /* A Java atomic, not a `Ref`: the exports run on the host's main thread outside any fibre, and reading a `Ref` there
   * would need another `unsafeRun*`. The program writes it once (`publish`), `tw_shutdown` swaps it back to `Idle`, and
   * the other exports only read it.
   */
  private val state: AtomicReference[Lifecycle] = new AtomicReference(Lifecycle.Idle)

  private val ComputeThreads = 2

  private val clock: IO[EpochSeconds] = IO.realTime.map(d => EpochSeconds(d.toSeconds))

  private val entryFailureFile: Option[os.Path] = SimulatedEntryFailure.flagFile(Env.system)

  def start(configJson: String, callback: CFuncPtr2[CString, Ptr[Byte], Unit], ctx: Ptr[Byte]): Int =
    if (Thread.currentThread().threadId() =!= 0L) Entry.WrongThread
    else
      state.get() match {
        case Lifecycle.Starting(_) | Lifecycle.Running(_, _) => Entry.Ok
        case Lifecycle.Idle =>
          codecs.readEither[Config](configJson) match {
            case Left(_) => Entry.BadConfig
            case Right(config) =>
              /* Not `IORuntime.builder()`: on Scala Native (cats-effect 3.7.1) the builder has no setter for the compute
               * thread count, `setCompute` drops the kqueue poller, and it adds a separate blocking pool. This call
               * expresses the 2-thread pool with the kqueue poller directly.
               */
              val (compute, poller, shutdownCompute) =
                IORuntime.createWorkStealingComputeThreadPool(threads = ComputeThreads, shutdownTimeout = 1.second)
              val runtime = IORuntime(compute, compute, compute, List(poller), shutdownCompute, IORuntimeConfig())
              state.set(Lifecycle.Starting(shutdownCompute))
              program(config, callback, ctx).unsafeRunAndForget()(using runtime)
              Entry.Ok
          }
      }

  private def program(config: Config, callback: CFuncPtr2[CString, Ptr[Byte], Unit], ctx: Ptr[Byte]): IO[Unit] =
    for {
      _         <- CurlHttp.globalInit
      bridge    <- Bridge.make(callback, ctx)
      providers <- Providers.live
      _         <- run(state, config, providers, bridge, new FileStateStore(config.stateDir), clock)
    } yield ()

  /** The runtime-facing core of the program, shared with `AppRuntimeSpec`: creates the sequential dispatcher and the
    * command queue as resources, publishes them, and runs the poller until `Command.Shutdown`. If `tw_shutdown` already
    * swapped the state back to `Idle` before publication, the poller is not started and the resources are released at
    * once.
    */
  private[app] def run(
    state: AtomicReference[Lifecycle],
    config: Config,
    providers: List[UsageProvider],
    sink: EnvelopeSink,
    store: StateStore,
    clock: IO[EpochSeconds],
  ): IO[Unit] =
    (Dispatcher.sequential[IO], Resource.eval(Queue.unbounded[IO, Command])).tupled.use {
      case (dispatcher, queue) =>
        IO(publish(state, new Handles(dispatcher, queue)))
          .ifM(Poller.run(config, providers, queue, sink, store, clock), IO.unit)
    }

  private def publish(state: AtomicReference[Lifecycle], handles: Handles): Boolean =
    state.updateAndGet {
      case Lifecycle.Starting(stop) => Lifecycle.Running(handles, stop)
      case other @ (Lifecycle.Idle | Lifecycle.Running(_, _)) => other
    } match {
      case Lifecycle.Running(_, _) => true
      case Lifecycle.Idle | Lifecycle.Starting(_) => false
    }

  def refresh(): Int = {
    SimulatedEntryFailure.check(entryFailureFile)
    submit(state, Command.refresh)
  }

  def setConfig(configJson: String): Int =
    codecs.readEither[Config](configJson) match {
      case Left(_) => Entry.BadConfig
      case Right(config) => submit(state, Command.setConfig(config))
    }

  /** Enqueues the shutdown through the dispatcher, then runs the runtime shutdown thunk while Unmanaged because it
    * parks.
    */
  def shutdown(): Int = shutdown(state)

  private[app] def shutdown(state: AtomicReference[Lifecycle]): Int =
    state.getAndSet(Lifecycle.Idle) match {
      case Lifecycle.Idle => Entry.Ok
      case Lifecycle.Starting(stop) =>
        /* The program has not published yet, so nothing can be enqueued; the 1 s pool shutdown ends it. */
        stopRuntime(stop)
        Entry.Ok
      case Lifecycle.Running(handles, stop) =>
        handles.dispatcher.unsafeRunAndForget(handles.queue.offer(Command.shutdown))
        stopRuntime(stop)
        Entry.Ok
    }

  private def stopRuntime(stop: () => Unit): Unit = {
    GcState.set(MutatorState.Unmanaged)
    stop()
    GcState.yieldNow()
    GcState.set(MutatorState.Managed)
  }

  /** Submits from the host thread through the dispatcher and returns at once. `Internal` before `tw_start` and in the
    * window before the program has published its handles. If the program has ended on its own (it only ends on an
    * error in `globalInit`, `Bridge.make`, or `Providers.live`, since `Shutdown` is only sent by `tw_shutdown`, which
    * resets the state first), the closed dispatcher throws `IllegalStateException`, which `Entry.managed` reports as
    * `Internal`.
    */
  private[app] def submit(state: AtomicReference[Lifecycle], command: Command): Int =
    state.get() match {
      case Lifecycle.Running(handles, _) =>
        handles.dispatcher.unsafeRunAndForget(handles.queue.offer(command))
        Entry.Ok
      case Lifecycle.Idle | Lifecycle.Starting(_) => Entry.Internal
    }
}
