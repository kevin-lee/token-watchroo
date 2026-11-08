package tokenwatchroo.app

import cats.effect.IO
import cats.effect.std.Queue
import cats.syntax.all.*
import scala.concurrent.duration.*
import tokenwatchroo.core.*
import tokenwatchroo.providers.UsageProvider

/** The refresh loop. One tick selects and detects providers, fetches them in parallel, derives the snapshot, runs the alert
  * engine, persists the state before anything is emitted, and then emits the snapshot and every alert. A tick never
  * fails: a total failure becomes an error envelope. Between ticks the loop sleeps for the refresh interval unless a
  * command arrives first. A `Refresh` command runs the next tick as `FetchTrigger.Manual`, so providers bypass their
  * caches; the timer, the first tick, and a config change run as `Scheduled`. A `Refresh` that arrives within
  * `ManualRefreshWindow` after the end of the last manual tick is dropped, so a burst of clicks on "Refresh now" costs
  * one tick, and a dropped command does not move the next scheduled tick (issue #51). `SetConfig` and `Shutdown` are
  * never delayed.
  *
  * The providers are a registry built once. Each tick selects from it with `AgentChoice.of` and `Config.agents` (#8):
  * an agent without an entry is detected first, an enabled subscription entry is fetched without detection and gets the
  * plan badge of its entry, an enabled API entry gives an unavailable card without a call to the provider, and a
  * disabled entry gives no card. The snapshot lists the agents in the order of `Config.agents`, then the others in
  * `AgentId` order (#104). A `SetConfig` that changes what is fetched runs a tick at once. Any other change (order,
  * plan, interval) waits for the next scheduled tick, which comes one new interval after the last tick (#104).
  * `SetConfig` replaces only the config, so the providers and their caches (the Claude
  * plan badge, the last good snapshots), the alert state, and the schedule are kept.
  *
  * Each tick takes the `Messages` of `config.language` with `config.numberFormat` from the bundles loaded at start
  * (#10), for the card texts and the notification copy. The shell sends the same language and number format for the
  * whole run, so `AgentChoice.needsFetch` does not look at them.
  */
object Poller {

  /** What a tick does with one selected agent. */
  private enum Planned {
    case Fetch(provider: UsageProvider, plan: PlanKind)
    case Ready(snapshot: AgentSnapshot)
  }

  /** A `Refresh` that arrives within this many seconds after the end of a manual tick is dropped (issue #51). */
  val ManualRefreshWindow: Seconds = Seconds(10L)

  def run(
    initial: Config,
    bundles: MessageBundles,
    registry: List[UsageProvider],
    queue: Queue[IO, Command],
    sink: EnvelopeSink,
    store: StateStore,
    clock: IO[EpochSeconds],
  ): IO[Unit] = {
    def loop(config: Config, trigger: FetchTrigger, lastManualAt: Option[EpochSeconds]): IO[Unit] =
      for {
        _     <- tick(config, bundles, registry, sink, store, clock, trigger)
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
          case Left(Command.SetConfig(next)) =>
            if (AgentChoice.needsFetch(config, next, registry.map(_.id)))
              loop(next, FetchTrigger.Scheduled, lastManualAt)
            else idle(next, rescheduled(deadline, config, next), lastManualAt)
          case Left(Command.Shutdown) => IO.unit
          case Right(()) => loop(config, FetchTrigger.Scheduled, lastManualAt)
        }
      }

    loop(initial, FetchTrigger.Scheduled, none[EpochSeconds])
  }

  /** The next tick after a config change that needs no fetch: one new interval after the last tick. */
  def rescheduled(deadline: EpochSeconds, old: Config, next: Config): EpochSeconds =
    deadline.plus(Seconds(next.refreshInterval.value.toLong - old.refreshInterval.value.toLong))

  private def withinManualWindow(lastManualAt: Option[EpochSeconds], at: EpochSeconds): Boolean =
    lastManualAt.exists(last => last.secondsUntil(at) < ManualRefreshWindow.value)

  def tick(
    config: Config,
    bundles: MessageBundles,
    registry: List[UsageProvider],
    sink: EnvelopeSink,
    store: StateStore,
    clock: IO[EpochSeconds],
    trigger: FetchTrigger,
  ): IO[Unit] = {
    val messages = bundles.of(config.language).withNumberFormat(config.numberFormat)
    val refresh  =
      for {
        now     <- clock
        planned <-
          AgentChoice.ordered(config, registry)(_.id).traverseFilter(provider => plan(provider, config, now, messages))
        agents  <- planned.parTraverse {
                     case Planned.Fetch(provider, kind) =>
                       provider.fetch(now, config, trigger, messages).map(_.withPlan(kind))
                     case Planned.Ready(snapshot) => IO.pure(snapshot)
                   }
        snapshot = Snapshot.of(now, agents)
        state <- store.read
        stepped        = AlertEngine.step(
                           state,
                           snapshot,
                           now,
                           AlertLevels.of(config, _),
                           SpendAlertLevels.of(config, _),
                           messages,
                         )
        (next, alerts) = stepped
        _ <- store.write(next)
        _ <- sink.emit(seq => Envelope.snapshot(seq, snapshot))
        _ <- alerts.traverse_(alert => sink.emit(seq => Envelope.alert(seq, alert)))
      } yield ()
    refresh.handleErrorWith { e =>
      sink.emit(seq => Envelope.error(seq, s"${e.getClass.getName}: ${Option(e.getMessage).getOrElse("")}"))
    }
  }

  /** Detection runs one provider after the other, as before #8. */
  private def plan(
    provider: UsageProvider,
    config: Config,
    now: EpochSeconds,
    messages: Messages,
  ): IO[Option[Planned]] =
    AgentChoice.of(config, provider.id) match {
      case AgentChoice.AutoDetect =>
        provider
          .detect(config)
          .map(detection => Option.when(detection.isDetected)(Planned.Fetch(provider, PlanKind.Individual)))
      case AgentChoice.Fetch(kind) => IO.pure(Planned.Fetch(provider, kind).some)
      case AgentChoice.ApiNotSupported =>
        IO.pure(
          Planned.Ready(AgentSnapshot.unavailable(provider.id, now, AgentChoice.apiNotSupportedMessage(messages))).some
        )
      case AgentChoice.Hidden => IO.pure(none[Planned])
    }
}
