package tokenwatchroo.providers

import cats.effect.IO
import cats.syntax.all.*
import extras.cats.syntax.all.*
import refined4s.types.all.*
import tokenwatchroo.core.*
import tokenwatchroo.core.codecs.given
import tokenwatchroo.core.providers.{CodexOAuth, CodexRollout, CodexUsageResponse}

/** Codex on a ChatGPT plan: `auth.json` token, then the usage endpoint, with the rollout logs as a fallback. */
final class CodexProvider(
  http: HttpClient,
  auth: CodexAuthReader,
  rollouts: RolloutFiles,
  env: Env,
  appVersion: String,
) extends UsageProvider {

  override def id: AgentId = AgentId.Codex

  override def detect(config: Config): IO[Detection] =
    CodexHomeResolver.resolve(config, env) match {
      case Left(_) => IO.pure(Detection.NotDetected)
      case Right(home) =>
        auth.read(home).map {
          case Left(ProviderError.CredentialsMissing) => Detection.NotDetected
          case Left(
                 ProviderError.CredentialsAccessDenied | ProviderError.CredentialsMalformed(_) |
                 ProviderError.TokenExpired(_) | ProviderError.RateLimited | ProviderError.Http(_) |
                 ProviderError.Network(_) | ProviderError.Decode(_) | ProviderError.Timeout |
                 ProviderError.Unsupported(_)
               ) =>
            Detection.Detected
          case Right(_) => Detection.Detected
        }
    }

  override def fetch(now: EpochSeconds, config: Config, trigger: FetchTrigger): IO[AgentSnapshot] =
    CodexHomeResolver.resolve(config, env) match {
      case Left(error) => IO.pure(AgentSnapshot.unavailable(id, now, error.toErrorMessage(CodexProvider.Cli)))
      case Right(home) =>
        fromApi(home, now).flatMap {
          case Right(snapshot) => IO.pure(snapshot)
          case Left(error) => fromLocalLog(home, now, error)
        }
    }

  private def fromApi(home: CodexHome, now: EpochSeconds): IO[Either[ProviderError, AgentSnapshot]] = {
    val result =
      for {
        oauth    <- auth.read(home).eitherT
        response <- http
                      .get(
                        CodexProvider.UsageUrl,
                        headers(oauth),
                        CodexProvider.userAgent(appVersion),
                        HttpClient.StandardTimeout
                      )
                      .eitherT
        usage    <-
          codecs.readEither[CodexUsageResponse](response.body).leftMap(e => ProviderError.decode(e.message)).eitherT[IO]
      } yield AgentSnapshot.available(id, usage.planLabel, usage.toWindows, Source.Api, now, none[ErrorMessage])
    result.value
  }

  /** The newest rollout log's last rate-limit snapshot, marked as coming from the local log with the API error kept. */
  private def fromLocalLog(home: CodexHome, now: EpochSeconds, apiError: ProviderError): IO[AgentSnapshot] =
    rollouts
      .newest(home)
      .flatMap(_.traverse(rollouts.readLines))
      .map(_.flatMap(lines => CodexRollout.latestRateLimits(lines.iterator)))
      .map {
        case Some(rateLimits) =>
          AgentSnapshot.available(
            id,
            rateLimits.planLabel,
            rateLimits.toWindows,
            Source.LocalLog,
            now,
            apiError.toErrorMessage(CodexProvider.Cli).some,
          )
        case None => AgentSnapshot.unavailable(id, now, apiError.toErrorMessage(CodexProvider.Cli))
      }

  private def headers(oauth: CodexOAuth): List[(String, String)] =
    List("Authorization" -> s"Bearer ${oauth.accessToken.value.value}", "Accept" -> "application/json") ++
      oauth.accountId.map(accountId => "ChatGPT-Account-Id" -> accountId.value.value).toList
}

object CodexProvider {

  val Cli: String      = "codex"
  val UsageUrl: String = "https://chatgpt.com/backend-api/wham/usage"

  def userAgent(appVersion: String): UserAgent =
    UserAgent(NonEmptyString.unsafeFrom(s"token-watchroo/${appVersion.trim}"))
}
