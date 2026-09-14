package tokenwatchroo.app

import cats.effect.{IO, Ref}
import cats.effect.std.Queue
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import refined4s.types.all.*
import scala.concurrent.duration.*
import tokenwatchroo.core.*
import tokenwatchroo.providers.UsageProvider

class PollerSpec extends munit.FunSuite {

  /** Fails a hung test by name instead of blocking the run. `timeoutAndForget` does not wait for an uninterruptible
    * `IO.blocking` to finish, which `timeout` would.
    */
  private val TestTimeout = 30.seconds

  private val now      = EpochSeconds(1789185600L)
  private val resetsAt = EpochSeconds(1789189920L)
  private val config   =
    Config(
      StateDir(NonEmptyString("/tmp/token-watchroo-app-tests")),
      RefreshIntervalSeconds.clamp(15),
      none[CodexHome],
      none[ClaudeCodeVersion]
    )

  final private class Stub(
    agent: AgentId,
    detection: Detection,
    fetchResult: (EpochSeconds, FetchTrigger) => IO[AgentSnapshot],
  ) extends UsageProvider {
    override def id: AgentId                                                                        = agent
    override def detect(config: Config): IO[Detection]                                              = IO.pure(detection)
    override def fetch(now: EpochSeconds, config: Config, trigger: FetchTrigger): IO[AgentSnapshot] =
      fetchResult(now, trigger)
  }

  /** A provider whose detection a test flips between ticks. A fetch while not detected fails the tick. */
  final private class Switchable(agent: AgentId, detection: Ref[IO, Detection], percent: Double) extends UsageProvider {
    override def id: AgentId                                                                        = agent
    override def detect(config: Config): IO[Detection]                                              = detection.get
    override def fetch(now: EpochSeconds, config: Config, trigger: FetchTrigger): IO[AgentSnapshot] =
      detection.get.flatMap {
        case Detection.Detected =>
          IO.pure(
            AgentSnapshot.available(
              agent,
              none[PlanLabel],
              UsageMeters(List(window(percent)), none[Spend]),
              Source.Api,
              now,
              none[ErrorMessage],
            )
          )
        case Detection.NotDetected =>
          IO.raiseError(new IllegalStateException(s"${agent.displayName} must not be fetched"))
      }
  }

  final private class RecordingSink(ref: Ref[IO, List[Envelope]]) extends EnvelopeSink {
    override def emit(build: SequenceNumber => Envelope): IO[Unit] =
      ref.update(envelopes => envelopes :+ build(SequenceNumber(envelopes.size.toLong + 1L)))
  }

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

  private def claudeAt(percent: Double): UsageProvider =
    new Stub(AgentId.ClaudeCode, Detection.Detected, (at, _) => IO.pure(claudeSnapshot(percent, at)))

  /** Records the trigger of every fetch. */
  private def claudeRecording(percent: Double, triggers: Ref[IO, List[FetchTrigger]]): UsageProvider =
    new Stub(
      AgentId.ClaudeCode,
      Detection.Detected,
      (at, trigger) => triggers.update(_ :+ trigger).as(claudeSnapshot(percent, at)),
    )

  private val codexDown: UsageProvider =
    new Stub(
      AgentId.Codex,
      Detection.Detected,
      (at, _) =>
        IO.pure(AgentSnapshot.unavailable(AgentId.Codex, at, ErrorMessage(NonEmptyString("Network error: curl 6")))),
    )

  private val notDetected: UsageProvider =
    new Stub(
      AgentId.Codex,
      Detection.NotDetected,
      (_, _) => IO.raiseError(new IllegalStateException("must not be fetched")),
    )

  private def setup: IO[(Ref[IO, List[Envelope]], RecordingSink, StateStore)] =
    for {
      ref   <- Ref.of[IO, List[Envelope]](Nil)
      store <- InMemoryStateStore.make
    } yield (ref, new RecordingSink(ref), store)

  private def snapshots(envelopes: List[Envelope]): List[Snapshot] =
    envelopes.collect { case Envelope.Snapshot(_, data) => data }

  private def alerts(envelopes: List[Envelope]): List[Alert] =
    envelopes.collect { case Envelope.Alert(_, data) => data }

  /** Sets Claude Code and Codex detection before each tick and returns every envelope. */
  private def ticks(steps: List[(Detection, Detection)]): List[Envelope] =
    (for {
      (ref, sink, store) <- setup
      claude             <- Ref.of[IO, Detection](Detection.Detected)
      codex              <- Ref.of[IO, Detection](Detection.Detected)
      providers = List(new Switchable(AgentId.ClaudeCode, claude, 10.0d), new Switchable(AgentId.Codex, codex, 40.0d))
      _   <- steps.traverse_ {
               case (claudeDetection, codexDetection) =>
                 claude.set(claudeDetection) >> codex.set(codexDetection) >>
                   Poller.tick(config, providers, sink, store, IO.pure(now), FetchTrigger.Scheduled)
             }
      all <- ref.get
    } yield all).timeoutAndForget(TestTimeout).unsafeRunSync()

  test("one tick emits one snapshot for the detected providers, then the alerts, and the second tick only a snapshot") {
    val program         =
      for {
        (ref, sink, store) <- setup
        providers = List(claudeAt(85.0d), codexDown, notDetected)
        _      <- Poller.tick(config, providers, sink, store, IO.pure(now), FetchTrigger.Scheduled)
        first  <- ref.get
        _      <- Poller.tick(config, providers, sink, store, IO.pure(now), FetchTrigger.Scheduled)
        second <- ref.get
      } yield (first, second)
    val (first, second) = program.timeoutAndForget(TestTimeout).unsafeRunSync()
    assertEquals(first.map(_.wire), List("snapshot", "alert"))
    assertEquals(snapshots(first).flatMap(_.agents.map(_.id)), List(AgentId.ClaudeCode, AgentId.Codex))
    assertEquals(snapshots(first).map(_.menubar.kind), List(MenubarKind.Warning))
    assertEquals(alerts(first).map(_.kind), List(AlertKind.Warning80))
    assertEquals(first.map(_.seq.value), List(1L, 2L))
    assertEquals(second.drop(2).map(_.wire), List("snapshot"))
  }

  test("a refresh command shortens the wait and a shutdown command ends the loop") {
    def awaitSnapshots(ref: Ref[IO, List[Envelope]], count: Int): IO[Unit] =
      ref
        .get
        .map(envelopes => snapshots(envelopes).size >= count)
        .ifM(IO.unit, IO.sleep(20.millis) >> awaitSnapshots(ref, count))

    val program     =
      for {
        (ref, sink, store) <- setup
        triggers           <- Ref.of[IO, List[FetchTrigger]](Nil)
        queue              <- Queue.unbounded[IO, Command]
        provider = claudeRecording(10.0d, triggers)
        fiber <- Poller.run(config, List(provider), queue, sink, store, IO.pure(now)).start
        _     <- awaitSnapshots(ref, 1).timeout(5.seconds)
        _     <- queue.offer(Command.refresh)
        _     <- awaitSnapshots(ref, 2).timeout(3.seconds)
        _     <- queue.offer(Command.shutdown)
        _     <- fiber.joinWithNever.timeout(5.seconds)
        all   <- ref.get
        seen  <- triggers.get
      } yield (all, seen)
    val (all, seen) = program.timeoutAndForget(TestTimeout).unsafeRunSync()
    assertEquals(snapshots(all).size, 2)
    assertEquals(alerts(all), Nil)
    assertEquals(seen, List(FetchTrigger.Scheduled, FetchTrigger.Manual))
  }

  test("a provider that forces garbage collection during the tick still delivers") {
    val gcHeavy: UsageProvider =
      new Stub(
        AgentId.ClaudeCode,
        Detection.Detected,
        (at, _) =>
          IO.blocking {
            val junk = (1 to 2000).map(i => List.fill(50)(i.toString)).toList
            System.gc()
            junk.size
          }.map(_ =>
            AgentSnapshot
              .available(
                AgentId.ClaudeCode,
                none[PlanLabel],
                UsageMeters(List(window(96.0d)), none[Spend]),
                Source.Api,
                at,
                none[ErrorMessage],
              )
          ),
      )
    val program                =
      for {
        (ref, sink, store) <- setup
        _                  <- (1 to 5)
                                .toList
                                .traverse_(_ => Poller.tick(config, List(gcHeavy), sink, store, IO.pure(now), FetchTrigger.Scheduled))
        all                <- ref.get
      } yield all
    val all                    = program.timeoutAndForget(TestTimeout).unsafeRunSync()
    assertEquals(snapshots(all).size, 5)
    assertEquals(alerts(all).map(_.kind), List(AlertKind.Critical95))
  }

  test("a failing state store turns the tick into an error envelope instead of a crash") {
    val broken: StateStore = new StateStore {
      override def read: IO[AlertState]               = IO.raiseError(new IllegalStateException("disk gone"))
      override def write(state: AlertState): IO[Unit] = IO.unit
    }
    val program            =
      for {
        ref <- Ref.of[IO, List[Envelope]](Nil)
        sink = new RecordingSink(ref)
        _   <- Poller.tick(config, List(claudeAt(10.0d)), sink, broken, IO.pure(now), FetchTrigger.Scheduled)
        all <- ref.get
      } yield all
    val all                = program.timeoutAndForget(TestTimeout).unsafeRunSync()
    assertEquals(all.map(_.wire), List("error"))
    assert(all.collect { case Envelope.Error(_, message) => message }.exists(_.contains("disk gone")))
  }

  test("an agent that is no longer detected drops out of the next snapshot, whichever one signs out") {
    val all = ticks(
      List(
        (Detection.Detected, Detection.Detected),
        (Detection.NotDetected, Detection.Detected),
        (Detection.Detected, Detection.Detected),
        (Detection.Detected, Detection.NotDetected),
      )
    )
    assertEquals(all.map(_.wire), List.fill(4)("snapshot"))
    assertEquals(
      snapshots(all).map(_.agents.map(_.id)),
      List(
        List(AgentId.ClaudeCode, AgentId.Codex),
        List(AgentId.Codex),
        List(AgentId.ClaudeCode, AgentId.Codex),
        List(AgentId.ClaudeCode),
      ),
    )
  }

  test(
    "with no agent detected the snapshot is empty with an unavailable menubar, and agents that sign in again come back"
  ) {
    val all = ticks(
      List(
        (Detection.Detected, Detection.Detected),
        (Detection.NotDetected, Detection.NotDetected),
        (Detection.NotDetected, Detection.Detected),
        (Detection.Detected, Detection.Detected),
      )
    )
    assertEquals(all.map(_.wire), List.fill(4)("snapshot"))
    assertEquals(
      snapshots(all).map(_.agents.map(_.id)),
      List(List(AgentId.ClaudeCode, AgentId.Codex), Nil, List(AgentId.Codex), List(AgentId.ClaudeCode, AgentId.Codex)),
    )
    assertEquals(
      snapshots(all).map(_.menubar.kind),
      List(MenubarKind.Normal, MenubarKind.Unavailable, MenubarKind.Normal, MenubarKind.Normal),
    )
  }
}
