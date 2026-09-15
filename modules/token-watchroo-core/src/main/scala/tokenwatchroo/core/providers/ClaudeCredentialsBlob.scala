package tokenwatchroo.core.providers

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*
import refined4s.types.all.*
import tokenwatchroo.core.*
import tokenwatchroo.core.codecs.given

/** The `claudeAiOauth` entry of the "Claude Code-credentials" keychain item. `rateLimitTier` is camelCase in the
  * keychain (verified 2026-09-12), and the blob codec has no field-name mapper.
  */
final case class ClaudeAiOauth(
  accessToken: Option[String],
  expiresAt: Option[Long],
  scopes: Option[List[String]],
  subscriptionType: Option[String],
  rateLimitTier: Option[String],
) derives CanEqual,
      Eq,
      Show

object ClaudeAiOauth {
  extension (oauth: ClaudeAiOauth) {

    /** The login-time snapshot of the plan, the offline fallback for the badge. When `subscriptionType` is present it
      * is the plan word and `rateLimitTier` is only a multiplier source. Without a word the tier alone decides.
      */
    def toPlan: Option[ClaudePlan] = oauth.subscriptionType match {
      case Some(word) => ClaudePlan.fromSubscriptionType(word, oauth.rateLimitTier)
      case None => oauth.rateLimitTier.flatMap(ClaudePlan.fromRateLimitTier)
    }
  }
}

/** The JSON payload stored as the keychain password. It may hold only MCP state and no OAuth entry. */
final case class ClaudeCredentialsBlob(claudeAiOauth: Option[ClaudeAiOauth]) derives CanEqual, Eq, Show

/** What the provider needs from the keychain. */
final case class ClaudeOAuth(
  accessToken: AccessToken,
  expiresAtMillis: Option[Long],
  profileScope: ProfileScope,
  planLabel: Option[PlanLabel],
) derives CanEqual,
      Eq,
      Show

object ClaudeCredentialsBlob {

  /** Parses the keychain payload. An expired token is rejected here so no network call is made with it. */
  def parse(raw: String, nowMillis: Long): Either[String, ClaudeOAuth] =
    for {
      blob  <- codecs.readEither[ClaudeCredentialsBlob](raw).leftMap(_.message)
      oauth <- blob
                 .claudeAiOauth
                 .toRight("Keychain item has no Claude sign-in (only MCP state). Run claude to sign in.")
      token <- oauth
                 .accessToken
                 .flatMap(t => NonEmptyString.from(t).toOption)
                 .map(AccessToken(_))
                 .toRight("Keychain item has no access token. Run claude to sign in.")
      _     <- Either.cond(
                 oauth.expiresAt.forall(_ > nowMillis),
                 (),
                 "Claude Code token expired. Run claude to sign in again.",
               )
    } yield ClaudeOAuth(
      token,
      oauth.expiresAt,
      ProfileScope.fromScopes(oauth.scopes.getOrElse(Nil)),
      oauth.toPlan.map(_.label).orElse(oauth.subscriptionType.flatMap(PlanLabel.fromPlanType)),
    )
}
