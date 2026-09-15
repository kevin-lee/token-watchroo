package tokenwatchroo.providers

import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import tokenwatchroo.core.*

class CodexProviderSpec extends munit.FunSuite {

  private val config = Fakes.configWithCodexHome("/tmp/token-watchroo-tests/codex")

  private def provider(http: HttpClient, auth: CodexAuthReader, rollouts: RolloutFiles): CodexProvider =
    new CodexProvider(http, auth, rollouts, Fakes.env, "0.1.0")

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
    assertEquals(p.detect(config).unsafeRunSync(), Detection.Detected)
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
