package tokenwatchroo.providers

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import extras.cats.syntax.all.*
import refined4s.types.all.*
import scala.concurrent.duration.*
import tokenwatchroo.core.*
import tokenwatchroo.core.codecs.given
import tokenwatchroo.core.providers.{ClaudeOAuth, ClaudeProfileResponse, ClaudeUsageResponse}

/** Claude Code on a subscription plan: keychain token, then the OAuth usage endpoint, with the profile endpoint called
  * next to it for the plan badge. The profile result is cached per access token for `ProfileTtl` on scheduled ticks
  * and refetched on a manual refresh, and a profile failure of any kind never affects availability. Badge order:
  * profile, keychain tier, keychain subscription type. Never refreshes the token. A usage-based Enterprise response
  * gives a spend meter instead of windows, and a response with neither is unavailable.
  */
final class ClaudeCodeProvider private (
  http: HttpClient,
  credentials: ClaudeCredentialReader,
  detectedVersion: IO[Option[ClaudeCodeVersion]],
  nowMillis: IO[Long],
  attempt: Ref[IO, ReadAttempt],
  profile: Ref[IO, Option[ProfileCacheEntry]],
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

  override def fetch(now: EpochSeconds, config: Config, trigger: FetchTrigger): IO[AgentSnapshot] = {
    val result =
      for {
        oauth   <- readCredentials.eitherT
        version <- resolveVersion(config).rightT[ProviderError]
        pair    <- IO
                     .both(
                       http.get(
                         ClaudeCodeProvider.UsageUrl,
                         headers(oauth),
                         ClaudeCodeProvider.userAgent(version),
                         HttpClient.StandardTimeout,
                       ),
                       profileLabel(oauth, version, now, trigger),
                     )
                     .map { case (usage, label) => usage.map(response => (response, label)) }
                     .eitherT
        (response, label) = pair
        usage  <- codecs
                    .readEither[ClaudeUsageResponse](response.body)
                    .leftMap(e => ProviderError.decode(e.message))
                    .eitherT[IO]
        meters <- usage.toMeters(now).leftMap(e => ProviderError.decode(e.message)).eitherT[IO]
        _      <- Either.cond(!meters.isEmpty, (), ProviderError.noUsageLimit).eitherT[IO]
      } yield AgentSnapshot.available(id, label.orElse(oauth.planLabel), meters, Source.Api, now, none[ErrorMessage])
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

  /** The cached label when the entry is fresh for this token and trigger, else one lookup. A decoded response
    * replaces the entry even when it names no plan. A failure replaces the entry too, keeping the previous label for
    * the same token, so a dead endpoint costs one call per TTL and a manual refresh retries at once.
    */
  private def profileLabel(
    oauth: ClaudeOAuth,
    version: ClaudeCodeVersion,
    now: EpochSeconds,
    trigger: FetchTrigger,
  ): IO[Option[PlanLabel]] =
    profile.get.flatMap {
      case Some(entry) if entry.isFresh(oauth.accessToken, now, ClaudeCodeProvider.ProfileTtl, trigger) =>
        IO.pure(entry.label)
      case cached @ (Some(_) | None) =>
        fetchProfile(oauth, version).flatMap {
          case Right(label) => profile.set(ProfileCacheEntry(oauth.accessToken, label, now).some).as(label)
          case Left(_) =>
            val carried = cached.filter(_.token === oauth.accessToken).flatMap(_.label)
            profile.set(ProfileCacheEntry(oauth.accessToken, carried, now).some).as(carried)
        }
    }

  /** Never raises: a thrown error becomes a `Network` failure so it cannot cancel the usage side of `IO.both`. A 401
    * or 403 arrives as `TokenExpired` from the client and is a plain failure here.
    */
  private def fetchProfile(
    oauth: ClaudeOAuth,
    version: ClaudeCodeVersion
  ): IO[Either[ProviderError, Option[PlanLabel]]] =
    http
      .get(
        ClaudeCodeProvider.ProfileUrl,
        headers(oauth),
        ClaudeCodeProvider.userAgent(version),
        ClaudeCodeProvider.ProfileTimeout,
      )
      .map(_.flatMap { response =>
        codecs
          .readEither[ClaudeProfileResponse](response.body)
          .leftMap(e => ProviderError.decode(e.message))
          .map(_.planLabel)
      })
      .handleError(e =>
        ProviderError.network(Option(e.getMessage).getOrElse(e.getClass.getName)).asLeft[Option[PlanLabel]]
      )

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
  val ProfileUrl: String = "https://api.anthropic.com/api/oauth/profile"
  val BetaHeader: String = "oauth-2025-04-20"

  /** The badge is cosmetic, so the profile gets a short budget. */
  val ProfileTimeout: FiniteDuration = 5.seconds

  /** Scheduled ticks wait this long between profile attempts. */
  val ProfileTtl: Seconds = Seconds(3600L)

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
      profile  <- Ref.of[IO, Option[ProfileCacheEntry]](none[ProfileCacheEntry])
    } yield new ClaudeCodeProvider(http, credentials, memoized, nowMillis, attempt, profile)
}
