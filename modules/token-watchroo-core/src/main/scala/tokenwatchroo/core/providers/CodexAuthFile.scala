package tokenwatchroo.core.providers

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*
import com.github.plokhotnyuk.jsoniter_scala.macros.named
import refined4s.types.all.*
import tokenwatchroo.core.*
import tokenwatchroo.core.codecs.given

final case class CodexTokens(accessToken: Option[String], accountId: Option[String]) derives CanEqual, Eq, Show

/** `~/.codex/auth.json`. Key names verified on a real installation. */
final case class CodexAuthFile(
  authMode: Option[String],
  @named("OPENAI_API_KEY") openAiApiKey: Option[String],
  tokens: Option[CodexTokens],
) derives CanEqual,
      Eq,
      Show

/** What the provider needs from the auth file.
  *
  * `OAuth` keeps its own spelling, not `Oauth`: it is the protocol's name, short for Open Authorization, and the
  * usual spelling is easier to read.
  */
final case class CodexOAuth(accessToken: AccessToken, accountId: Option[AccountId]) derives CanEqual, Eq, Show

object CodexAuthFile {

  def parse(raw: String): Either[CredentialProblem, CodexOAuth] =
    for {
      file  <- codecs.readEither[CodexAuthFile](raw).leftMap(e => CredentialProblem.unreadable(e.message))
      token <- file
                 .tokens
                 .flatMap(_.accessToken)
                 .flatMap(t => NonEmptyString.from(t).toOption)
                 .map(AccessToken(_))
                 .toRight(
                   if (file.openAiApiKey.exists(_.nonEmpty)) CredentialProblem.codexApiKeyMode
                   else CredentialProblem.codexNoAccessToken
                 )
    } yield CodexOAuth(
      token,
      file.tokens.flatMap(_.accountId).flatMap(id => NonEmptyString.from(id).toOption).map(AccountId(_)),
    )
}
