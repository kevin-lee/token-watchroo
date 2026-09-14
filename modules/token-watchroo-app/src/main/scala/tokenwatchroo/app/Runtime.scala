package tokenwatchroo.app

import cats.effect.IO
import cats.effect.std.Queue
import cats.effect.unsafe.{IORuntime, IORuntimeConfig}
import cats.syntax.all.*
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*
import scala.scalanative.unsafe.*
import tokenwatchroo.core.*
import tokenwatchroo.core.codecs.given
import tokenwatchroo.providers.{CurlHttp, Env, Providers}

/** The running library: the cats-effect runtime, the command queue, and the runtime shutdown thunk. */
final class Started(val runtime: IORuntime, val queue: Queue[IO, Command], val shutdownRuntime: () => Unit)

/** What the exported entry points do. Every method runs inside the managed region of `Entry.managed` and returns a
  * return code. None of them ever blocks on a fibre: they enqueue commands with `unsafeRunAndForget`.
  */
object AppRuntime {

  private val started: AtomicReference[Option[Started]] = new AtomicReference(none[Started])

  private val ComputeThreads = 2

  private val clock: IO[EpochSeconds] = IO.realTime.map(d => EpochSeconds(d.toSeconds))

  private val entryFailureFile: Option[os.Path] = SimulatedEntryFailure.flagFile(Env.system)

  def start(configJson: String, callback: CFuncPtr2[CString, Ptr[Byte], Unit], ctx: Ptr[Byte]): Int =
    if (Thread.currentThread().threadId() =!= 0L) Entry.WrongThread
    else if (started.get().isDefined) Entry.Ok
    else
      codecs.readEither[Config](configJson) match {
        case Left(_) => Entry.BadConfig
        case Right(config) =>
          val (compute, poller, shutdownCompute) =
            IORuntime.createWorkStealingComputeThreadPool(threads = ComputeThreads, shutdownTimeout = 1.second)
          val runtime = IORuntime(compute, compute, compute, List(poller), shutdownCompute, IORuntimeConfig())
          /* Creating the queue has no asynchronous step, so this completes on the current thread without parking. */
          val queue   = Queue.unbounded[IO, Command].unsafeRunSync()(using runtime)
          val program =
            for {
              _         <- CurlHttp.globalInit
              bridge    <- Bridge.make(callback, ctx)
              providers <- Providers.live
              _         <- Poller.run(config, providers, queue, bridge, new FileStateStore(config.stateDir), clock)
            } yield ()
          started.set(Started(runtime, queue, shutdownCompute).some)
          program.unsafeRunAndForget()(using runtime)
          Entry.Ok
      }

  def refresh(): Int = {
    SimulatedEntryFailure.check(entryFailureFile)
    offer(Command.refresh)
  }

  def setConfig(configJson: String): Int =
    codecs.readEither[Config](configJson) match {
      case Left(_) => Entry.BadConfig
      case Right(config) => offer(Command.setConfig(config))
    }

  /** Enqueues the shutdown, then runs the runtime shutdown thunk while Unmanaged because it parks. */
  def shutdown(): Int =
    started.getAndSet(none[Started]) match {
      case None => Entry.Ok
      case Some(current) =>
        current.queue.offer(Command.shutdown).unsafeRunAndForget()(using current.runtime)
        GcState.set(MutatorState.Unmanaged)
        current.shutdownRuntime()
        GcState.yieldNow()
        GcState.set(MutatorState.Managed)
        Entry.Ok
    }

  private def offer(command: Command): Int =
    started.get() match {
      case None => Entry.Internal
      case Some(current) =>
        current.queue.offer(command).unsafeRunAndForget()(using current.runtime)
        Entry.Ok
    }
}
