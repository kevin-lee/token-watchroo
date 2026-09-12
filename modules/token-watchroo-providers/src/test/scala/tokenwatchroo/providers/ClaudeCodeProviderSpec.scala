package tokenwatchroo.providers

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import tokenwatchroo.core.*

class ClaudeCodeProviderSpec extends munit.FunSuite {

  private def provider(http: HttpClient, keychain: KeychainReader): ClaudeCodeProvider =
    ClaudeCodeProvider
      .make(http, new ClaudeCredentials(keychain), IO.pure(none[ClaudeCodeVersion]), IO.pure(1789185600000L))
      .unsafeRunSync()

  test("a readable keychain and a 200 give an available snapshot from the API") {
    val p = provider(new Fakes.FakeHttp(Fakes.ok(Fakes.claudeUsage)), new Fakes.FakeKeychain(Fakes.claudeBlob.asRight))
    val snapshot = p.fetch(Fakes.now, Fakes.config).unsafeRunSync()
    assertEquals(snapshot.status, AgentStatus.Ok)
    assertEquals(snapshot.source, Some(Source.Api))
    assertEquals(snapshot.planLabel.map(_.value.value), Some("Max"))
    assertEquals(
      snapshot.windows.map(w => (w.id, w.usedPercent.value)),
      List((WindowId.Session, 72.0d), (WindowId.Weekly, 38.0d))
    )
    assertEquals(p.detect(Fakes.config).unsafeRunSync(), Detection.Detected)
  }

  test("a 401 gives an unavailable snapshot that tells the user to sign in again") {
    val p        = provider(
      new Fakes.FakeHttp(ProviderError.tokenExpired(HttpStatus(401)).asLeft),
      new Fakes.FakeKeychain(Fakes.claudeBlob.asRight)
    )
    val snapshot = p.fetch(Fakes.now, Fakes.config).unsafeRunSync()
    assertEquals(snapshot.status, AgentStatus.Unavailable)
    assertEquals(snapshot.windows, Nil)
    assertEquals(snapshot.error.map(_.value.value), Some("Token expired (HTTP 401). Run claude to sign in again."))
  }

  test("a network failure gives an unavailable snapshot") {
    val p        = provider(
      new Fakes.FakeHttp(ProviderError.network("curl 28: Timeout was reached").asLeft),
      new Fakes.FakeKeychain(Fakes.claudeBlob.asRight)
    )
    val snapshot = p.fetch(Fakes.now, Fakes.config).unsafeRunSync()
    assertEquals(snapshot.error.map(_.value.value), Some("Network error: curl 28: Timeout was reached"))
  }

  test("a missing keychain item is not detected and never calls the API") {
    val p = provider(
      new Fakes.FakeHttp(ProviderError.network("must not be called").asLeft),
      new Fakes.FakeKeychain(ProviderError.credentialsMissing.asLeft)
    )
    assertEquals(p.detect(Fakes.config).unsafeRunSync(), Detection.NotDetected)
    assertEquals(
      p.fetch(Fakes.now, Fakes.config).unsafeRunSync().error.map(_.value.value),
      Some("Not signed in. Run claude once.")
    )
  }

  test("a token without the profile scope is unsupported") {
    val blob =
      """{"claudeAiOauth":{"accessToken":"sk-ant-oat01-x","expiresAt":4102444800000,"scopes":["user:inference"]}}"""
    val p    = provider(new Fakes.FakeHttp(Fakes.ok(Fakes.claudeUsage)), new Fakes.FakeKeychain(blob.asRight))
    assert(p.fetch(Fakes.now, Fakes.config).unsafeRunSync().error.exists(_.value.value.contains("user:profile")))
  }

  test("the User-Agent follows claude-code/<version>") {
    assertEquals(ClaudeCodeProvider.userAgent(ClaudeCodeVersion.fallback).value.value, "claude-code/2.1.0")
    assertEquals(ClaudeCli.parseVersion("2.1.269 (Claude Code)").map(_.value.render), Some("2.1.269"))
    assertEquals(ClaudeCli.parseVersion("nothing here"), None)
  }
}
