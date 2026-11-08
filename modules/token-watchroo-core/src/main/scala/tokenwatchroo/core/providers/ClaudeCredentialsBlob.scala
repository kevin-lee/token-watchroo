package tokenwatchroo.core.providers

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*
import refined4s.types.all.*
import tokenwatchroo.core.*
import tokenwatchroo.core.codecs.given

/** Whether `claudeAiOauth.refreshToken` is empty. Claude Code writes `""` when the server rejects the refresh token
  * (#94). The value itself is never kept, because Token Watchroo never refreshes: a second app that refreshes signs the
  * CLI out.
  */
enum RefreshTokenState derives CanEqual, Eq, Show {
  case Empty
  case Present
}

object RefreshTokenState {
  def empty: RefreshTokenState   = RefreshTokenState.Empty
  def present: RefreshTokenState = RefreshTokenState.Present

  def fromRaw(raw: String): RefreshTokenState =
    if (raw.isEmpty) RefreshTokenState.Empty else RefreshTokenState.Present
}

/** The `claudeAiOauth` entry of the "Claude Code-credentials" keychain item. `rateLimitTier` is camelCase in the
  * keychain (verified 2026-09-12), and the blob codec has no field-name mapper. `refreshToken` keeps only whether the
  * token is empty or present. `loginEnded` is `"time_limit"` when the sign-in reached its time limit.
  *
  * The type is `ClaudeAiOAuth`, not `ClaudeAiOauth` like the JSON key: `OAuth` is the protocol's name, short for Open
  * Authorization. The field `claudeAiOauth` in `ClaudeCredentialsBlob` keeps the key's spelling because the codec
  * reads the key by the field name.
  */
final case class ClaudeAiOAuth(
  accessToken: Option[String],
  refreshToken: Option[RefreshTokenState],
  expiresAt: Option[Long],
  scopes: Option[List[String]],
  subscriptionType: Option[String],
  rateLimitTier: Option[String],
  loginEnded: Option[String],
) derives CanEqual,
      Eq,
      Show

object ClaudeAiOAuth {
  extension (oauth: ClaudeAiOAuth) {

    /** Claude Code empties the refresh token when the server rejects it, and `/status` then says the login expired. A
      * missing refresh token is not an ended sign-in, the same as in Claude Code.
      */
    def signInEnded: Boolean =
      oauth.refreshToken.contains(RefreshTokenState.Empty) || oauth.loginEnded.isDefined

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
final case class ClaudeCredentialsBlob(claudeAiOauth: Option[ClaudeAiOAuth]) derives CanEqual, Eq, Show

/** What the provider needs from the keychain.
  *
  * `OAuth` keeps its own spelling, not `Oauth`: it is the protocol's name, short for Open Authorization, and the
  * usual spelling is easier to read.
  */
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
  def parse(raw: String, nowMillis: Long): Either[CredentialProblem, ClaudeOAuth] =
    for {
      blob  <- codecs.readEither[ClaudeCredentialsBlob](raw).leftMap(e => CredentialProblem.unreadable(e.message))
      oauth <- blob
                 .claudeAiOauth
                 .toRight(CredentialProblem.claudeNoSignIn)
      _     <- Either.cond(
                 !oauth.signInEnded,
                 (),
                 CredentialProblem.claudeSignInExpired,
               )
      token <- oauth
                 .accessToken
                 .flatMap(t => NonEmptyString.from(t).toOption)
                 .map(AccessToken(_))
                 .toRight(CredentialProblem.claudeNoAccessToken)
      _     <- Either.cond(
                 oauth.expiresAt.forall(_ > nowMillis),
                 (),
                 CredentialProblem.claudeTokenExpired,
               )
    } yield ClaudeOAuth(
      token,
      oauth.expiresAt,
      ProfileScope.fromScopes(oauth.scopes.getOrElse(Nil)),
      oauth.toPlan.map(_.label).orElse(oauth.subscriptionType.flatMap(PlanLabel.fromPlanType)),
    )
}
