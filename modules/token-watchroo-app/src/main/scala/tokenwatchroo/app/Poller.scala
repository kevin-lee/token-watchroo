package tokenwatchroo.app

import cats.effect.IO
import cats.effect.std.Queue
import cats.syntax.all.*
import scala.concurrent.duration.*
import tokenwatchroo.core.*
import tokenwatchroo.providers.UsageProvider

/** The refresh loop. One tick detects providers, fetches them in parallel, derives the snapshot, runs the alert
  * engine, persists the state before anything is emitted, and then emits the snapshot and every alert. A tick never
  * fails: a total failure becomes an error envelope. Between ticks the loop sleeps for the refresh interval unless a
  * command arrives first. A `Refresh` command runs the next tick as `FetchTrigger.Manual`, so providers bypass their
  * caches; the timer, the first tick, and a config change run as `Scheduled`. A `Refresh` that arrives within
  * `ManualRefreshWindow` after the end of the last manual tick is dropped, so a burst of clicks on "Refresh now" costs
  * one tick, and a dropped command does not move the next scheduled tick (issue #51). `SetConfig` and `Shutdown` are
  * never delayed.
  */
object Poller {

  /** A `Refresh` that arrives within this many seconds after the end of a manual tick is dropped (issue #51). */
  val ManualRefreshWindow: Seconds = Seconds(10L)

  def run(
    initial: Config,
    providers: List[UsageProvider],
    queue: Queue[IO, Command],
    sink: EnvelopeSink,
    store: StateStore,
    clock: IO[EpochSeconds],
  ): IO[Unit] = {
    def loop(config: Config, trigger: FetchTrigger, lastManualAt: Option[EpochSeconds]): IO[Unit] =
      for {
        _     <- tick(config, providers, sink, store, clock, trigger)
        after <- clock
        last = trigger match {
                 case FetchTrigger.Manual => after.some
                 case FetchTrigger.Scheduled => lastManualAt
               }
        _ <- idle(config, after.plus(Seconds(config.refreshInterval.value.toLong)), last)
      } yield ()

    def idle(config: Config, deadline: EpochSeconds, lastManualAt: Option[EpochSeconds]): IO[Unit] =
      clock.flatMap { now =>
        IO.race(queue.take, IO.sleep(math.max(0L, now.secondsUntil(deadline)).seconds)).flatMap {
          case Left(Command.Refresh) =>
            clock.flatMap { at =>
              if (withinManualWindow(lastManualAt, at)) idle(config, deadline, lastManualAt)
              else loop(config, FetchTrigger.Manual, lastManualAt)
            }
          case Left(Command.SetConfig(next)) => loop(next, FetchTrigger.Scheduled, lastManualAt)
          case Left(Command.Shutdown) => IO.unit
          case Right(()) => loop(config, FetchTrigger.Scheduled, lastManualAt)
        }
      }

    loop(initial, FetchTrigger.Scheduled, none[EpochSeconds])
  }

  private def withinManualWindow(lastManualAt: Option[EpochSeconds], at: EpochSeconds): Boolean =
    lastManualAt.exists(last => last.secondsUntil(at) < ManualRefreshWindow.value)

  def tick(
    config: Config,
    providers: List[UsageProvider],
    sink: EnvelopeSink,
    store: StateStore,
    clock: IO[EpochSeconds],
    trigger: FetchTrigger,
  ): IO[Unit] = {
    val refresh =
      for {
        now      <- clock
        detected <- providers.filterA(_.detect(config).map(_.isDetected))
        agents   <- detected.parTraverse(_.fetch(now, config, trigger))
        snapshot = Snapshot.of(now, agents)
        state <- store.read
        stepped        = AlertEngine.step(state, snapshot, now)
        (next, alerts) = stepped
        _ <- store.write(next)
        _ <- sink.emit(seq => Envelope.snapshot(seq, snapshot))
        _ <- alerts.traverse_(alert => sink.emit(seq => Envelope.alert(seq, alert)))
      } yield ()
    refresh.handleErrorWith { e =>
      sink.emit(seq => Envelope.error(seq, s"${e.getClass.getName}: ${Option(e.getMessage).getOrElse("")}"))
    }
  }
}
