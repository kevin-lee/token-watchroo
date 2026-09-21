package tokenwatchroo.providers

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import extras.cats.syntax.all.*
import refined4s.types.all.*
import tokenwatchroo.core.*
import tokenwatchroo.core.codecs.given
import tokenwatchroo.core.providers.{CodexOAuth, CodexUsageResponse}

/** Codex on a ChatGPT plan: `auth.json` token, then the usage endpoint, with the rollout logs as a fallback. A response
  * without `rate_limit` gives a credits spend meter, and a response with neither windows nor spend falls back too. On
  * a 429 after a good fetch the last good API snapshot for the same token comes back with the rate-limit text in
  * `error`, ahead of the rollout log; every other API error falls back to the log (issue #51).
  */
final class CodexProvider private (
  http: HttpClient,
  auth: CodexAuthReader,
  rollouts: RolloutFiles,
  env: Env,
  appVersion: String,
  lastGood: Ref[IO, Option[LastGoodSnapshot]],
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
        auth.read(home).flatMap {
          case Left(error) => fromLocalLog(home, now, error)
          case Right(oauth) =>
            fromApi(oauth, now).flatMap {
              case Right(snapshot) => lastGood.set(LastGoodSnapshot(oauth.accessToken, snapshot).some).as(snapshot)
              case Left(ProviderError.RateLimited) =>
                lastGood.get.flatMap {
                  _.flatMap(_.carriedFor(oauth.accessToken, CodexProvider.RateLimitedNote)) match {
                    case Some(carried) => IO.pure(carried)
                    case None => fromLocalLog(home, now, ProviderError.RateLimited)
                  }
                }
              case Left(
                     error @ (ProviderError.CredentialsMissing | ProviderError.CredentialsAccessDenied |
                     ProviderError.CredentialsMalformed(_) | ProviderError.TokenExpired(_) | ProviderError.Http(_) |
                     ProviderError.Network(_) | ProviderError.Decode(_) | ProviderError.Timeout |
                     ProviderError.Unsupported(_))
                   ) =>
                fromLocalLog(home, now, error)
            }
        }
    }

  private def fromApi(oauth: CodexOAuth, now: EpochSeconds): IO[Either[ProviderError, AgentSnapshot]] = {
    val result =
      for {
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
        meters   <- usage.toMeters.leftMap(e => ProviderError.decode(e.message)).eitherT[IO]
        _        <- Either.cond(!meters.isEmpty, (), ProviderError.noUsageLimit).eitherT[IO]
      } yield AgentSnapshot.available(id, usage.planLabel, meters, Source.Api, now, none[ErrorMessage])
    result.value
  }

  /** The newest rollout log's last rate-limit snapshot, marked as coming from the local log with the API error kept. */
  private def fromLocalLog(home: CodexHome, now: EpochSeconds, apiError: ProviderError): IO[AgentSnapshot] =
    rollouts
      .newest(home)
      .flatMap(_.flatTraverse(rollouts.latestRateLimits))
      .map {
        case Some(rateLimits) =>
          AgentSnapshot.available(
            id,
            rateLimits.planLabel,
            UsageMeters(rateLimits.toWindows, none[Spend]),
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

  private val RateLimitedNote: ErrorMessage = ProviderError.rateLimited.toErrorMessage(Cli)

  def userAgent(appVersion: String): UserAgent =
    UserAgent(NonEmptyString.unsafeFrom(s"token-watchroo/${appVersion.trim}"))

  def make(
    http: HttpClient,
    auth: CodexAuthReader,
    rollouts: RolloutFiles,
    env: Env,
    appVersion: String,
  ): IO[CodexProvider] =
    Ref
      .of[IO, Option[LastGoodSnapshot]](none[LastGoodSnapshot])
      .map(lastGood => new CodexProvider(http, auth, rollouts, env, appVersion, lastGood))
}
