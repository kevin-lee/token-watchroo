package tokenwatchroo.providers

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import scala.concurrent.duration.*
import tokenwatchroo.core.*

class ClaudeCodeProviderSpec extends munit.FunSuite {

  private val UsageUrl   = ClaudeCodeProvider.UsageUrl
  private val ProfileUrl = ClaudeCodeProvider.ProfileUrl

  private def make(http: HttpClient, keychain: KeychainReader): IO[ClaudeCodeProvider] =
    ClaudeCodeProvider
      .make(http, new ClaudeCredentials(keychain), IO.pure(none[ClaudeCodeVersion]), IO.pure(1789185600000L))

  private def provider(http: HttpClient, keychain: KeychainReader): ClaudeCodeProvider =
    make(http, keychain).unsafeRunSync()

  private def at(offset: Long): EpochSeconds = EpochSeconds(Fakes.now.value + offset)

  private def label(snapshot: AgentSnapshot): Option[String] = snapshot.planLabel.map(_.value.value)

  /** A provider wired to a routing fake answering the usage URL with the fixture and the profile URL as given. */
  private def withRoutes[A](profile: Either[ProviderError, HttpResponse], keychain: KeychainReader)(
    program: (ClaudeCodeProvider, Fakes.RoutingHttp) => IO[A]
  ): A =
    (for {
      http <- Fakes.RoutingHttp.make(Fakes.claudeRoutes(profile))
      p    <- make(http, keychain)
      a    <- program(p, http)
    } yield a).unsafeRunSync()

  private def scheduled(p: ClaudeCodeProvider, now: EpochSeconds): IO[AgentSnapshot] =
    p.fetch(now, Fakes.config, FetchTrigger.Scheduled)

  private def manual(p: ClaudeCodeProvider, now: EpochSeconds): IO[AgentSnapshot] =
    p.fetch(now, Fakes.config, FetchTrigger.Manual)

  test("a readable keychain and a 200 give an available snapshot from the API") {
    val (snapshot, detection, usageCalls, profileCalls) =
      withRoutes(Fakes.ok(Fakes.claudeProfile20x), new Fakes.FakeKeychain(Fakes.claudeBlob.asRight)) { (p, http) =>
        for {
          snapshot  <- scheduled(p, Fakes.now)
          detection <- p.detect(Fakes.config)
          usage     <- http.callsTo(UsageUrl)
          profile   <- http.callsTo(ProfileUrl)
        } yield (snapshot, detection, usage, profile)
      }
    assertEquals(snapshot.status, AgentStatus.Ok)
    assertEquals(snapshot.source, Some(Source.Api))
    assertEquals(label(snapshot), Some("Max 20x"))
    assertEquals(
      snapshot.windows.map(w => (w.id, w.usedPercent.value)),
      List((WindowId.Session, 72.0d), (WindowId.Weekly, 38.0d))
    )
    assertEquals(detection, Detection.Detected)
    assertEquals(usageCalls, 1)
    assertEquals(profileCalls, 1)
  }

  test("a 401 gives an unavailable snapshot that tells the user to sign in again") {
    val p        = provider(
      new Fakes.FakeHttp(ProviderError.tokenExpired(HttpStatus(401)).asLeft),
      new Fakes.FakeKeychain(Fakes.claudeBlob.asRight)
    )
    val snapshot = scheduled(p, Fakes.now).unsafeRunSync()
    assertEquals(snapshot.status, AgentStatus.Unavailable)
    assertEquals(snapshot.windows, Nil)
    assertEquals(snapshot.error.map(_.value.value), Some("Token expired (HTTP 401). Run claude to sign in again."))
  }

  test("a network failure gives an unavailable snapshot") {
    val p        = provider(
      new Fakes.FakeHttp(ProviderError.network("curl 28: Timeout was reached").asLeft),
      new Fakes.FakeKeychain(Fakes.claudeBlob.asRight)
    )
    val snapshot = scheduled(p, Fakes.now).unsafeRunSync()
    assertEquals(snapshot.error.map(_.value.value), Some("Network error: curl 28: Timeout was reached"))
  }

  test("a missing keychain item is not detected and never calls the API") {
    val (detection, error, usageCalls, profileCalls) =
      withRoutes(Fakes.ok(Fakes.claudeProfile20x), new Fakes.FakeKeychain(ProviderError.credentialsMissing.asLeft)) {
        (p, http) =>
          for {
            detection <- p.detect(Fakes.config)
            snapshot  <- scheduled(p, Fakes.now)
            usage     <- http.callsTo(UsageUrl)
            profile   <- http.callsTo(ProfileUrl)
          } yield (detection, snapshot.error.map(_.value.value), usage, profile)
      }
    assertEquals(detection, Detection.NotDetected)
    assertEquals(error, Some("Not signed in. Run claude once."))
    assertEquals(usageCalls, 0)
    assertEquals(profileCalls, 0)
  }

  test("a token without the profile scope is unsupported") {
    val blob =
      """{"claudeAiOauth":{"accessToken":"sk-ant-oat01-x","expiresAt":4102444800000,"scopes":["user:inference"]}}"""
    val p    = provider(new Fakes.FakeHttp(Fakes.ok(Fakes.claudeUsage)), new Fakes.FakeKeychain(blob.asRight))
    assert(scheduled(p, Fakes.now).unsafeRunSync().error.exists(_.value.value.contains("user:profile")))
  }

  test("the User-Agent follows claude-code/<version>") {
    assertEquals(ClaudeCodeProvider.userAgent(ClaudeCodeVersion.fallback).value.value, "claude-code/2.1.0")
    assertEquals(ClaudeCli.parseVersion("2.1.269 (Claude Code)").map(_.value.render), Some("2.1.269"))
    assertEquals(ClaudeCli.parseVersion("nothing here"), None)
  }

  private def keychainBadgeSurvives(profile: Either[ProviderError, HttpResponse]): Unit = {
    val snapshot =
      withRoutes(profile, new Fakes.FakeKeychain(Fakes.claudeBlobWithTier.asRight))((p, _) => scheduled(p, Fakes.now))
    assertEquals(snapshot.status, AgentStatus.Ok)
    assertEquals(label(snapshot), Some("Max 5x"))
    assertEquals(snapshot.error, None)
  }

  test("a profile 500 keeps the keychain badge and the agent stays available") {
    keychainBadgeSurvives(ProviderError.http(HttpStatus(500)).asLeft)
  }

  test("a profile 401 never expires the agent") {
    keychainBadgeSurvives(ProviderError.tokenExpired(HttpStatus(401)).asLeft)
  }

  test("a profile timeout keeps the keychain badge") {
    keychainBadgeSurvives(Fakes.curlTimeout)
  }

  test("an unreadable profile body keeps the keychain badge") {
    keychainBadgeSurvives(Fakes.ok("not json"))
  }

  test("without a keychain tier the fallback is the subscription type") {
    val snapshot =
      withRoutes(ProviderError.http(HttpStatus(500)).asLeft, new Fakes.FakeKeychain(Fakes.claudeBlob.asRight)) {
        (p, _) => scheduled(p, Fakes.now)
      }
    assertEquals(label(snapshot), Some("Max"))
  }

  test("a scheduled fetch within the hour reuses the cached profile") {
    val (first, second, usageCalls, profileCalls) =
      withRoutes(Fakes.ok(Fakes.claudeProfile20x), new Fakes.FakeKeychain(Fakes.claudeBlobWithTier.asRight)) {
        (p, http) =>
          for {
            first   <- scheduled(p, Fakes.now)
            second  <- scheduled(p, at(3599L))
            usage   <- http.callsTo(UsageUrl)
            profile <- http.callsTo(ProfileUrl)
          } yield (label(first), label(second), usage, profile)
      }
    assertEquals(first, Some("Max 20x"))
    assertEquals(second, Some("Max 20x"))
    assertEquals(usageCalls, 2)
    assertEquals(profileCalls, 1)
  }

  test("an empty profile is cached too") {
    val (first, second, profileCalls) =
      withRoutes(Fakes.ok("{}"), new Fakes.FakeKeychain(Fakes.claudeBlobWithTier.asRight)) { (p, http) =>
        for {
          first   <- scheduled(p, Fakes.now)
          second  <- scheduled(p, at(60L))
          profile <- http.callsTo(ProfileUrl)
        } yield (label(first), label(second), profile)
      }
    assertEquals(first, Some("Max 5x"))
    assertEquals(second, Some("Max 5x"))
    assertEquals(profileCalls, 1)
  }

  test("a manual refresh fetches the profile again") {
    val profileCalls =
      withRoutes(Fakes.ok(Fakes.claudeProfile20x), new Fakes.FakeKeychain(Fakes.claudeBlobWithTier.asRight)) {
        (p, http) => scheduled(p, Fakes.now) >> manual(p, Fakes.now) >> http.callsTo(ProfileUrl)
      }
    assertEquals(profileCalls, 2)
  }

  test("a scheduled fetch after an hour fetches the profile again") {
    val profileCalls =
      withRoutes(Fakes.ok(Fakes.claudeProfile20x), new Fakes.FakeKeychain(Fakes.claudeBlobWithTier.asRight)) {
        (p, http) => scheduled(p, Fakes.now) >> scheduled(p, at(3600L)) >> http.callsTo(ProfileUrl)
      }
    assertEquals(profileCalls, 2)
  }

  test("a profile failure after a success keeps the cached label and waits an hour before retrying") {
    val (labels, statuses, calls) =
      withRoutes(Fakes.ok(Fakes.claudeProfile20x), new Fakes.FakeKeychain(Fakes.claudeBlobWithTier.asRight)) {
        (p, http) =>
          for {
            first  <- scheduled(p, Fakes.now)
            _      <- http.set(ProfileUrl, ProviderError.http(HttpStatus(500)).asLeft)
            second <- manual(p, Fakes.now)
            after2 <- http.callsTo(ProfileUrl)
            third  <- scheduled(p, at(60L))
            after3 <- http.callsTo(ProfileUrl)
            fourth <- scheduled(p, at(3600L))
            after4 <- http.callsTo(ProfileUrl)
          } yield (
            List(first, second, third, fourth).map(label),
            List(first, second, third, fourth).map(_.status),
            List(after2, after3, after4),
          )
      }
    assertEquals(labels, List.fill(4)(Some("Max 20x")))
    assertEquals(statuses, List.fill(4)(AgentStatus.Ok))
    assertEquals(calls, List(2, 2, 3))
  }

  test("a token change refetches the profile") {
    val profileCalls =
      (for {
        http     <- Fakes.RoutingHttp.make(Fakes.claudeRoutes(Fakes.ok(Fakes.claudeProfile20x)))
        keychain <-
          Fakes.SequencedKeychain.make(List(Fakes.claudeBlobWithTier.asRight, Fakes.claudeBlobOtherToken.asRight))
        p        <- make(http, keychain)
        _        <- scheduled(p, Fakes.now)
        _        <- scheduled(p, Fakes.now)
        calls    <- http.callsTo(ProfileUrl)
      } yield calls).unsafeRunSync()
    assertEquals(profileCalls, 2)
  }

  test("a usage 401 stays unavailable even when the profile is fine") {
    val snapshot =
      (for {
        http <- Fakes
                  .RoutingHttp
                  .make(
                    Map(
                      UsageUrl   -> ProviderError.tokenExpired(HttpStatus(401)).asLeft[HttpResponse],
                      ProfileUrl -> Fakes.ok(Fakes.claudeProfile20x),
                    )
                  )
        p    <- make(http, new Fakes.FakeKeychain(Fakes.claudeBlobWithTier.asRight))
        s    <- scheduled(p, Fakes.now)
      } yield s).unsafeRunSync()
    assertEquals(snapshot.status, AgentStatus.Unavailable)
    assertEquals(snapshot.planLabel, None)
    assertEquals(snapshot.error.map(_.value.value), Some("Token expired (HTTP 401). Run claude to sign in again."))
  }

  test("the profile call carries the usage headers and the short timeout") {
    val (profileRequest, usageRequest) =
      withRoutes(Fakes.ok(Fakes.claudeProfile20x), new Fakes.FakeKeychain(Fakes.claudeBlobWithTier.asRight)) {
        (p, http) =>
          for {
            _       <- scheduled(p, Fakes.now)
            profile <- http.lastRequest(ProfileUrl)
            usage   <- http.lastRequest(UsageUrl)
          } yield (profile, usage)
      }
    assertEquals(
      profileRequest.map(_.headers),
      Some(
        List(
          "Authorization"  -> "Bearer sk-ant-oat01-example",
          "anthropic-beta" -> "oauth-2025-04-20",
          "Accept"         -> "application/json",
        )
      ),
    )
    assertEquals(profileRequest.map(_.userAgent.value.value), Some("claude-code/2.1.0"))
    assertEquals(profileRequest.map(_.timeout), Some(5.seconds))
    assertEquals(usageRequest.map(_.timeout), Some(20.seconds))
  }
}
