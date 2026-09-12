package tokenwatchroo.providers

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import extras.cats.syntax.all.*
import refined4s.types.all.*
import tokenwatchroo.core.*
import tokenwatchroo.core.codecs.given
import tokenwatchroo.core.providers.{ClaudeOAuth, ClaudeUsageResponse}

/** Claude Code on a subscription plan: keychain token, then the OAuth usage endpoint. Never refreshes the token. */
final class ClaudeCodeProvider private (
  http: HttpClient,
  credentials: ClaudeCredentialReader,
  detectedVersion: IO[Option[ClaudeCodeVersion]],
  nowMillis: IO[Long],
  attempt: Ref[IO, ReadAttempt],
) extends UsageProvider {

  override def id: AgentId = AgentId.ClaudeCode

  override def detect(config: Config): IO[Detection] =
    readCredentials.map {
      case Left(ProviderError.CredentialsMissing) => Detection.NotDetected
      case Left(
             ProviderError.CredentialsAccessDenied | ProviderError.CredentialsMalformed(_) |
             ProviderError.TokenExpired(_) | ProviderError.RateLimited | ProviderError.Http(_) |
             ProviderError.Network(_) | ProviderError.Decode(_) | ProviderError.Timeout | ProviderError.Unsupported(_)
           ) =>
        Detection.Detected
      case Right(_) => Detection.Detected
    }

  override def fetch(now: EpochSeconds, config: Config): IO[AgentSnapshot] = {
    val result =
      for {
        oauth    <- readCredentials.eitherT
        version  <- resolveVersion(config).rightT[ProviderError]
        response <- http.get(ClaudeCodeProvider.UsageUrl, headers(oauth), ClaudeCodeProvider.userAgent(version)).eitherT
        usage    <- codecs
                      .readEither[ClaudeUsageResponse](response.body)
                      .leftMap(e => ProviderError.decode(e.message))
                      .eitherT[IO]
        windows  <- usage.toWindows.leftMap(e => ProviderError.decode(e.message)).eitherT[IO]
      } yield AgentSnapshot.available(id, oauth.planLabel, windows, Source.Api, now, none[ErrorMessage])
    result
      .value
      .map(_.fold(error => AgentSnapshot.unavailable(id, now, error.toErrorMessage(ClaudeCodeProvider.Cli)), identity))
  }

  private def readCredentials: IO[Either[ProviderError, ClaudeOAuth]] =
    for {
      current <- attempt.getAndSet(ReadAttempt.Subsequent)
      millis  <- nowMillis
      result  <- credentials.read(current, millis)
    } yield result

  private def resolveVersion(config: Config): IO[ClaudeCodeVersion] =
    config.claudeCodeVersionOverride match {
      case Some(version) => IO.pure(version)
      case None => detectedVersion.map(_.getOrElse(ClaudeCodeVersion.fallback))
    }

  private def headers(oauth: ClaudeOAuth): List[(String, String)] =
    List(
      "Authorization"  -> s"Bearer ${oauth.accessToken.value.value}",
      "anthropic-beta" -> ClaudeCodeProvider.BetaHeader,
      "Accept"         -> "application/json",
    )
}

object ClaudeCodeProvider {

  val Cli: String        = "claude"
  val UsageUrl: String   = "https://api.anthropic.com/api/oauth/usage"
  val BetaHeader: String = "oauth-2025-04-20"

  def userAgent(version: ClaudeCodeVersion): UserAgent =
    UserAgent(NonEmptyString.unsafeFrom(s"claude-code/${version.value.render}"))

  /** `detectedVersion` is evaluated at most once per process through `memoize`. */
  def make(
    http: HttpClient,
    credentials: ClaudeCredentialReader,
    detectVersion: IO[Option[ClaudeCodeVersion]],
    nowMillis: IO[Long],
  ): IO[ClaudeCodeProvider] =
    for {
      memoized <- detectVersion.memoize
      attempt  <- Ref.of[IO, ReadAttempt](ReadAttempt.First)
    } yield new ClaudeCodeProvider(http, credentials, memoized, nowMillis, attempt)
}
