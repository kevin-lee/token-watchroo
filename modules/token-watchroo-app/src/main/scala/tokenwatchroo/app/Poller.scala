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
  * command arrives first.
  */
object Poller {

  def run(
    initial: Config,
    providers: List[UsageProvider],
    queue: Queue[IO, Command],
    sink: EnvelopeSink,
    store: StateStore,
    clock: IO[EpochSeconds],
  ): IO[Unit] = {
    def loop(config: Config): IO[Unit] =
      tick(config, providers, sink, store, clock) >>
        IO.race(queue.take, IO.sleep(config.refreshInterval.value.seconds)).flatMap {
          case Left(Command.Refresh) => loop(config)
          case Left(Command.SetConfig(next)) => loop(next)
          case Left(Command.Shutdown) => IO.unit
          case Right(()) => loop(config)
        }
    loop(initial)
  }

  def tick(
    config: Config,
    providers: List[UsageProvider],
    sink: EnvelopeSink,
    store: StateStore,
    clock: IO[EpochSeconds],
  ): IO[Unit] = {
    val refresh =
      for {
        now      <- clock
        detected <- providers.filterA(_.detect(config).map(_.isDetected))
        agents   <- detected.parTraverse(_.fetch(now, config))
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
