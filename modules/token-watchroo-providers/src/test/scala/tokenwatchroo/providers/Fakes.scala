package tokenwatchroo.providers

import cats.effect.IO
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

  val claudeUsage: String =
    """{"five_hour":{"utilization":72.0,"resets_at":"2026-09-12T09:12:00Z"},"seven_day":{"utilization":38.0,"resets_at":"2026-09-15T00:00:00Z"}}"""

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
      userAgent: UserAgent
    ): IO[Either[ProviderError, HttpResponse]] =
      IO.pure(response)
  }

  def ok(body: String): Either[ProviderError, HttpResponse] = HttpResponse(HttpStatus(200), body).asRight[ProviderError]

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
