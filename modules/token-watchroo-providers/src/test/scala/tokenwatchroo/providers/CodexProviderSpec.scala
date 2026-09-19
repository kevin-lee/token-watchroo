package tokenwatchroo.providers

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import tokenwatchroo.core.*

class CodexProviderSpec extends munit.FunSuite {

  private val config          = Fakes.configWithCodexHome("/tmp/token-watchroo-tests/codex")
  private val UsageUrl        = CodexProvider.UsageUrl
  private val RateLimitedText = "Usage API rate limited. Retrying next refresh."

  private def provider(http: HttpClient, auth: CodexAuthReader, rollouts: RolloutFiles): CodexProvider =
    CodexProvider.make(http, auth, rollouts, Fakes.env, "0.1.0").unsafeRunSync()

  private def at(offset: Long): EpochSeconds = EpochSeconds(Fakes.now.value + offset)

  private def fetch(p: CodexProvider, now: EpochSeconds, trigger: FetchTrigger): IO[AgentSnapshot] =
    p.fetch(now, config, trigger)

  /** A provider wired to a routing fake answering the usage URL as given. */
  private def withRouting[A](usage: Either[ProviderError, HttpResponse], auth: CodexAuthReader, rollouts: RolloutFiles)(
    program: (CodexProvider, Fakes.RoutingHttp) => IO[A]
  ): A =
    (for {
      http <- Fakes.RoutingHttp.make(Map(UsageUrl -> usage))
      p    <- CodexProvider.make(http, auth, rollouts, Fakes.env, "0.1.0")
      a    <- program(p, http)
    } yield a).unsafeRunSync()

  test("an Enterprise payload with rate_limit null gives a credits spend meter") {
    val p        = provider(
      new Fakes.FakeHttp(Fakes.ok(UsageFixtures.codexEnterpriseUsage)),
      new Fakes.FakeCodexAuth(Fakes.codexOAuth.asRight),
      new Fakes.FakeRollouts(None)
    )
    val snapshot = p.fetch(Fakes.now, config, FetchTrigger.Scheduled).unsafeRunSync()
    assertEquals(snapshot.status, AgentStatus.Ok)
    assertEquals(snapshot.source, Some(Source.Api))
    assertEquals(snapshot.planLabel.map(_.value.value), Some("Enterprise"))
    assertEquals(snapshot.windows, Nil)
    assertEquals(
      snapshot.spend.map(s => (s.currency, s.spent.plainString, s.limit.plainString, s.resetsAt)),
      Some((Currency.credits, "8000", "25000", Some(EpochSeconds(1778137680L))))
    )
  }

  test("rate_limit null without a spend control and without a rollout log is unavailable with the no-limit message") {
    val p        = provider(
      new Fakes.FakeHttp(Fakes.ok(UsageFixtures.codexRateLimitNullNoSpend)),
      new Fakes.FakeCodexAuth(Fakes.codexOAuth.asRight),
      new Fakes.FakeRollouts(None)
    )
    val snapshot = p.fetch(Fakes.now, config, FetchTrigger.Scheduled).unsafeRunSync()
    assertEquals(snapshot.status, AgentStatus.Unavailable)
    assertEquals(snapshot.error.map(_.value.value), Some("No usage limit reported by the usage API"))
  }

  test("rate_limit null without a spend control falls back to the rollout log") {
    val p        = provider(
      new Fakes.FakeHttp(Fakes.ok(UsageFixtures.codexRateLimitNullNoSpend)),
      new Fakes.FakeCodexAuth(Fakes.codexOAuth.asRight),
      new Fakes.FakeRollouts(Some(List(Fakes.rolloutLine)))
    )
    val snapshot = p.fetch(Fakes.now, config, FetchTrigger.Scheduled).unsafeRunSync()
    assertEquals(snapshot.source, Some(Source.LocalLog))
    assertEquals(snapshot.windows.map(_.usedPercent.value), List(17.0d, 6.0d))
    assertEquals(snapshot.spend, None)
    assertEquals(snapshot.error.map(_.value.value), Some("No usage limit reported by the usage API"))
  }

  test("a readable auth file and a 200 give an available snapshot from the API") {
    val p        = provider(
      new Fakes.FakeHttp(Fakes.ok(Fakes.codexUsage)),
      new Fakes.FakeCodexAuth(Fakes.codexOAuth.asRight),
      new Fakes.FakeRollouts(None)
    )
    val snapshot = p.fetch(Fakes.now, config, FetchTrigger.Scheduled).unsafeRunSync()
    assertEquals(snapshot.status, AgentStatus.Warning)
    assertEquals(snapshot.source, Some(Source.Api))
    assertEquals(snapshot.planLabel.map(_.value.value), Some("Plus"))
    assertEquals(
      snapshot.windows.map(w => (w.id, w.usedPercent.value)),
      List((WindowId.Session, 82.0d), (WindowId.Weekly, 55.0d))
    )
    assertEquals(
      snapshot.windows.map(_.resetsAt),
      List(Some(EpochSeconds(1789187040L)), Some(EpochSeconds(1789617600L)))
    )
    assertEquals(p.detect(config).unsafeRunSync(), Detection.Detected)
  }

  test("the verified Team payload gives session and weekly windows with reset times") {
    val p        = provider(
      new Fakes.FakeHttp(Fakes.ok(UsageFixtures.codexTeamUsage)),
      new Fakes.FakeCodexAuth(Fakes.codexOAuth.asRight),
      new Fakes.FakeRollouts(None)
    )
    val snapshot = p.fetch(Fakes.now, config, FetchTrigger.Scheduled).unsafeRunSync()
    assertEquals(snapshot.status, AgentStatus.Ok)
    assertEquals(snapshot.source, Some(Source.Api))
    assertEquals(snapshot.planLabel.map(_.value.value), Some("Team"))
    assertEquals(
      snapshot.windows.map(w => (w.id, w.usedPercent.value, w.resetsAt)),
      List(
        (WindowId.Session, 74.0d, Some(EpochSeconds(1789801452L))),
        (WindowId.Weekly, 27.0d, Some(EpochSeconds(1789807246L)))
      )
    )
    assertEquals(snapshot.spend, None)
  }

  test("an API failure falls back to the rollout log and keeps the error") {
    val p        = provider(
      new Fakes.FakeHttp(ProviderError.network("curl 6").asLeft),
      new Fakes.FakeCodexAuth(Fakes.codexOAuth.asRight),
      new Fakes.FakeRollouts(Some(List(Fakes.rolloutLine)))
    )
    val snapshot = p.fetch(Fakes.now, config, FetchTrigger.Scheduled).unsafeRunSync()
    assertEquals(snapshot.source, Some(Source.LocalLog))
    assertEquals(snapshot.status, AgentStatus.Ok)
    assertEquals(snapshot.planLabel.map(_.value.value), Some("Prolite"))
    assertEquals(snapshot.windows.map(_.usedPercent.value), List(17.0d, 6.0d))
    assertEquals(snapshot.error.map(_.value.value), Some("Network error: curl 6"))
  }

  test("an API failure without any rollout log is unavailable") {
    val p        = provider(
      new Fakes.FakeHttp(ProviderError.http(HttpStatus(500)).asLeft),
      new Fakes.FakeCodexAuth(Fakes.codexOAuth.asRight),
      new Fakes.FakeRollouts(None)
    )
    val snapshot = p.fetch(Fakes.now, config, FetchTrigger.Scheduled).unsafeRunSync()
    assertEquals(snapshot.status, AgentStatus.Unavailable)
    assertEquals(snapshot.error.map(_.value.value), Some("Usage API error HTTP 500"))
  }

  test("a 429 after a good fetch keeps the API meters and carries the note, ahead of the rollout log") {
    val (first, second) =
      withRouting(
        Fakes.ok(Fakes.codexUsage),
        new Fakes.FakeCodexAuth(Fakes.codexOAuth.asRight),
        new Fakes.FakeRollouts(Some(List(Fakes.rolloutLine))),
      ) { (p, http) =>
        for {
          first  <- fetch(p, Fakes.now, FetchTrigger.Scheduled)
          _      <- http.set(UsageUrl, ProviderError.rateLimited.asLeft)
          second <- fetch(p, at(5L), FetchTrigger.Manual)
        } yield (first, second)
      }
    assertEquals(second.source, Some(Source.Api))
    assertEquals(second.status, AgentStatus.Warning)
    assertEquals(second.windows, first.windows)
    assertEquals(second.planLabel.map(_.value.value), Some("Plus"))
    assertEquals(second.error.map(_.value.value), Some(RateLimitedText))
    assertEquals(second.fetchedAt, Fakes.now)
  }

  test("a 429 with no good fetch falls back to the rollout log with the note") {
    val p        = provider(
      new Fakes.FakeHttp(ProviderError.rateLimited.asLeft),
      new Fakes.FakeCodexAuth(Fakes.codexOAuth.asRight),
      new Fakes.FakeRollouts(Some(List(Fakes.rolloutLine)))
    )
    val snapshot = p.fetch(Fakes.now, config, FetchTrigger.Scheduled).unsafeRunSync()
    assertEquals(snapshot.source, Some(Source.LocalLog))
    assertEquals(snapshot.windows.map(_.usedPercent.value), List(17.0d, 6.0d))
    assertEquals(snapshot.error.map(_.value.value), Some(RateLimitedText))
  }

  test("a 429 with no good fetch and no rollout log is unavailable with the note") {
    val p        = provider(
      new Fakes.FakeHttp(ProviderError.rateLimited.asLeft),
      new Fakes.FakeCodexAuth(Fakes.codexOAuth.asRight),
      new Fakes.FakeRollouts(None)
    )
    val snapshot = p.fetch(Fakes.now, config, FetchTrigger.Scheduled).unsafeRunSync()
    assertEquals(snapshot.status, AgentStatus.Unavailable)
    assertEquals(snapshot.error.map(_.value.value), Some(RateLimitedText))
  }

  test("a 500 after a good fetch takes the rollout log, not the carried meters") {
    val second =
      withRouting(
        Fakes.ok(Fakes.codexUsage),
        new Fakes.FakeCodexAuth(Fakes.codexOAuth.asRight),
        new Fakes.FakeRollouts(Some(List(Fakes.rolloutLine))),
      ) { (p, http) =>
        fetch(p, Fakes.now, FetchTrigger.Scheduled) >>
          http.set(UsageUrl, ProviderError.http(HttpStatus(500)).asLeft) >>
          fetch(p, at(5L), FetchTrigger.Scheduled)
      }
    assertEquals(second.source, Some(Source.LocalLog))
    assertEquals(second.windows.map(_.usedPercent.value), List(17.0d, 6.0d))
    assertEquals(second.error.map(_.value.value), Some("Usage API error HTTP 500"))
  }

  test("a 429 after a token change does not carry the other account's meters") {
    val auth            =
      Fakes.SequencedCodexAuth.make(List(Fakes.codexOAuth.asRight, Fakes.codexOAuthOther.asRight)).unsafeRunSync()
    val (first, second) =
      withRouting(Fakes.ok(Fakes.codexUsage), auth, new Fakes.FakeRollouts(None)) { (p, http) =>
        for {
          first  <- fetch(p, Fakes.now, FetchTrigger.Scheduled)
          _      <- http.set(UsageUrl, ProviderError.rateLimited.asLeft)
          second <- fetch(p, at(5L), FetchTrigger.Scheduled)
        } yield (first, second)
      }
    assertEquals(first.status, AgentStatus.Warning)
    assertEquals(second.status, AgentStatus.Unavailable)
    assertEquals(second.error.map(_.value.value), Some(RateLimitedText))
  }

  test("a missing auth file is not detected") {
    val p = provider(
      new Fakes.FakeHttp(Fakes.ok(Fakes.codexUsage)),
      new Fakes.FakeCodexAuth(ProviderError.credentialsMissing.asLeft),
      new Fakes.FakeRollouts(None)
    )
    assertEquals(p.detect(config).unsafeRunSync(), Detection.NotDetected)
  }

  test("the Codex home resolves from config, then CODEX_HOME, then HOME") {
    val fromEnv  = CodexHomeResolver.resolve(Fakes.config, Env.fromMap(Map("HOME" -> "/h", "CODEX_HOME" -> "/c")))
    val fromHome = CodexHomeResolver.resolve(Fakes.config, Env.fromMap(Map("HOME" -> "/h")))
    assertEquals(
      CodexHomeResolver.resolve(config, Fakes.env).map(_.value.value),
      Right("/tmp/token-watchroo-tests/codex")
    )
    assertEquals(fromEnv.map(_.value.value), Right("/c"))
    assertEquals(fromHome.map(_.value.value), Right("/h/.codex"))
    assert(CodexHomeResolver.resolve(Fakes.config, Env.fromMap(Map.empty)).isLeft)
  }
}
