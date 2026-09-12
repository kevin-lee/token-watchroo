package tokenwatchroo.providers

import cats.{Eq, Show}
import cats.derived.*
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import java.nio.file.Path
import refined4s.types.all.*
import scala.concurrent.duration.FiniteDuration
import tokenwatchroo.core.*
import tokenwatchroo.core.providers.CodexOAuth

/** Test doubles for everything a provider touches outside the process. */
object Fakes {

  val now: EpochSeconds = EpochSeconds(1789185600L)

  val config: Config = Config.default(StateDir(NonEmptyString("/tmp/token-watchroo-tests")))

  def configWithCodexHome(home: String): Config =
    config.copy(codexHome = CodexHome(NonEmptyString.unsafeFrom(home)).some)

  val env: Env = Env.fromMap(Map("HOME" -> "/tmp/token-watchroo-tests/home"))

  val claudeBlob: String =
    """{"claudeAiOauth":{"accessToken":"sk-ant-oat01-example","refreshToken":"r","expiresAt":4102444800000,"scopes":["user:inference","user:profile"],"subscriptionType":"max"}}"""

  /** The same blob with the login-time tier, and the same again for another account. */
  val claudeBlobWithTier: String =
    """{"claudeAiOauth":{"accessToken":"sk-ant-oat01-example","refreshToken":"r","expiresAt":4102444800000,"scopes":["user:inference","user:profile"],"subscriptionType":"max","rateLimitTier":"default_claude_max_5x"}}"""

  val claudeBlobOtherToken: String =
    """{"claudeAiOauth":{"accessToken":"sk-ant-oat01-other","refreshToken":"r","expiresAt":4102444800000,"scopes":["user:inference","user:profile"],"subscriptionType":"max","rateLimitTier":"default_claude_max_5x"}}"""

  /** The verified profile shape, on a Max 20x organization. */
  val claudeProfile20x: String =
    """{"account":{"uuid":"a","full_name":"Test","display_name":"Test","email":"test@example.com","has_claude_max":true,"has_claude_pro":false},"organization":{"uuid":"o","name":"Org","organization_type":"claude_max","billing_type":"stripe_subscription","rate_limit_tier":"default_claude_max_20x","seat_tier":null,"subscription_status":"active"},"application":{"uuid":"p","name":"Claude Code","slug":"claude-code"},"enabled_plugins":[]}"""

  val claudeUsage: String =
    """{"five_hour":{"utilization":72.0,"resets_at":"2026-09-12T09:12:00Z"},"seven_day":{"utilization":38.0,"resets_at":"2026-09-15T00:00:00Z"}}"""

  /** The usage fixture plus a `limits` array with two scoped windows, Opus listed before Fable. */
  val claudeUsageWithLimits: String =
    """{"five_hour":{"utilization":72.0,"resets_at":"2026-09-12T09:12:00Z"},"seven_day":{"utilization":38.0,"resets_at":"2026-09-15T00:00:00Z"},"seven_day_opus":null,"seven_day_sonnet":null,"limits":[{"kind":"session","group":"session","percent":72,"severity":"normal","resets_at":"2026-09-12T09:12:00Z","scope":null,"is_active":false},{"kind":"weekly_all","group":"weekly","percent":38,"severity":"normal","resets_at":"2026-09-15T00:00:00Z","scope":null,"is_active":false},{"kind":"weekly_scoped","group":"weekly","percent":82,"severity":"normal","resets_at":"2026-09-15T00:00:00Z","scope":{"model":{"id":null,"display_name":"Opus"},"surface":null},"is_active":true},{"kind":"weekly_scoped","group":"weekly","percent":68,"severity":"normal","resets_at":"2026-09-15T00:00:00Z","scope":{"model":{"id":null,"display_name":"Fable"},"surface":null},"is_active":false}]}"""

  val codexAuth: String =
    """{"auth_mode":"chatgpt","OPENAI_API_KEY":null,"tokens":{"id_token":"x","access_token":"eyJ-access","refresh_token":"r","account_id":"acct-1"},"last_refresh":"2026-09-10T00:00:00Z"}"""

  val codexUsage: String =
    """{"plan_type":"plus","rate_limit":{"primary_window":{"used_percent":82,"limit_window_seconds":18000,"resets_at":1789187040},"secondary_window":{"used_percent":55,"limit_window_seconds":604800,"resets_at":1789617600}}}"""

  val rolloutLine: String =
    """{"timestamp":"2026-04-29T07:59:08.887Z","type":"event_msg","payload":{"type":"token_count","info":null,"rate_limits":{"limit_id":"codex","primary":{"used_percent":17.0,"window_minutes":300,"resets_at":1777477636},"secondary":{"used_percent":6.0,"window_minutes":10080,"resets_at":1777960801},"plan_type":"prolite"}}}"""

  final class FakeHttp(response: Either[ProviderError, HttpResponse]) extends HttpClient {
    override def get(
      url: String,
      headers: List[(String, String)],
      userAgent: UserAgent,
      timeout: FiniteDuration,
    ): IO[Either[ProviderError, HttpResponse]] =
      IO.pure(response)
  }

  def ok(body: String): Either[ProviderError, HttpResponse] = HttpResponse(HttpStatus(200), body).asRight[ProviderError]

  /** The exact text `CurlHttp` produces on a libcurl timeout. */
  val curlTimeout: Either[ProviderError, HttpResponse] =
    ProviderError.network("curl 28: Timeout was reached").asLeft[HttpResponse]

  def claudeRoutes(profile: Either[ProviderError, HttpResponse]): Map[String, Either[ProviderError, HttpResponse]] =
    Map(ClaudeCodeProvider.UsageUrl -> ok(claudeUsage), ClaudeCodeProvider.ProfileUrl -> profile)

  given Eq[FiniteDuration]   = Eq.fromUniversalEquals
  given Show[FiniteDuration] = Show.fromToString

  final case class RecordedRequest(headers: List[(String, String)], userAgent: UserAgent, timeout: FiniteDuration)
      derives CanEqual,
        Eq,
        Show

  /** Answers per URL, records every request, and lets a test change an answer between calls. */
  final class RoutingHttp(
    responses: Ref[IO, Map[String, Either[ProviderError, HttpResponse]]],
    calls: Ref[IO, Map[String, List[RecordedRequest]]],
  ) extends HttpClient {
    override def get(
      url: String,
      headers: List[(String, String)],
      userAgent: UserAgent,
      timeout: FiniteDuration,
    ): IO[Either[ProviderError, HttpResponse]] =
      calls.update(m => m.updated(url, m.getOrElse(url, Nil) :+ RecordedRequest(headers, userAgent, timeout))) >>
        responses.get.map(_.getOrElse(url, ProviderError.network(s"unexpected URL: $url").asLeft[HttpResponse]))

    def callsTo(url: String): IO[Int] = calls.get.map(_.getOrElse(url, Nil).size)

    def lastRequest(url: String): IO[Option[RecordedRequest]] = calls.get.map(_.getOrElse(url, Nil).lastOption)

    def set(url: String, response: Either[ProviderError, HttpResponse]): IO[Unit] =
      responses.update(_.updated(url, response))
  }

  object RoutingHttp {
    def make(initial: Map[String, Either[ProviderError, HttpResponse]]): IO[RoutingHttp] =
      for {
        responses <- Ref.of[IO, Map[String, Either[ProviderError, HttpResponse]]](initial)
        calls     <- Ref.of[IO, Map[String, List[RecordedRequest]]](Map.empty)
      } yield new RoutingHttp(responses, calls)
  }

  /** Each read returns the head of the list and drops it, keeping the last element forever. */
  final class SequencedKeychain(results: Ref[IO, List[Either[ProviderError, String]]]) extends KeychainReader {
    override def readGenericPassword(service: String, timeout: FiniteDuration): IO[Either[ProviderError, String]] =
      results.modify {
        case head :: next :: rest => (next :: rest, head)
        case head :: Nil => (head :: Nil, head)
        case Nil => (Nil, ProviderError.credentialsMissing.asLeft[String])
      }
  }

  object SequencedKeychain {
    def make(results: List[Either[ProviderError, String]]): IO[SequencedKeychain] =
      Ref.of[IO, List[Either[ProviderError, String]]](results).map(new SequencedKeychain(_))
  }

  final class FakeKeychain(result: Either[ProviderError, String]) extends KeychainReader {
    override def readGenericPassword(service: String, timeout: FiniteDuration): IO[Either[ProviderError, String]] =
      IO.pure(result)
  }

  final class FakeCodexAuth(result: Either[ProviderError, CodexOAuth]) extends CodexAuthReader {
    override def read(codexHome: CodexHome): IO[Either[ProviderError, CodexOAuth]] = IO.pure(result)
  }

  final class FakeRollouts(lines: Option[List[String]]) extends RolloutFiles {
    override def newest(codexHome: CodexHome): IO[Option[Path]] = IO.pure(lines.map(_ => Path.of("fake.jsonl")))
    override def readLines(path: Path): IO[List[String]]        = IO.pure(lines.getOrElse(Nil))
  }

  val codexOAuth: CodexOAuth =
    CodexOAuth(AccessToken(NonEmptyString("eyJ-access")), AccountId(NonEmptyString("acct-1")).some)
}
