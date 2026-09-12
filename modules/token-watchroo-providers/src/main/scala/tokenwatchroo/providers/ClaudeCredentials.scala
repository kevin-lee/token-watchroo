package tokenwatchroo.providers

import cats.effect.IO
import cats.syntax.all.*
import scala.concurrent.duration.*
import tokenwatchroo.core.*
import tokenwatchroo.core.providers.{ClaudeCredentialsBlob, ClaudeOAuth}

trait ClaudeCredentialReader {
  def read(attempt: ReadAttempt, nowMillis: Long): IO[Either[ProviderError, ClaudeOAuth]]
}

/** The Claude Code OAuth credentials from the keychain item `Claude Code-credentials`. */
final class ClaudeCredentials(keychain: KeychainReader) extends ClaudeCredentialReader {

  override def read(attempt: ReadAttempt, nowMillis: Long): IO[Either[ProviderError, ClaudeOAuth]] =
    keychain
      .readGenericPassword(ClaudeCredentials.Service, ClaudeCredentials.timeoutFor(attempt))
      .map(_.flatMap { raw =>
        ClaudeCredentialsBlob
          .parse(raw, nowMillis)
          .leftMap(ProviderError.credentialsMalformed)
          .flatMap { oauth =>
            oauth.profileScope match {
              case ProfileScope.Granted => oauth.asRight[ProviderError]
              case ProfileScope.Missing =>
                ProviderError
                  .unsupported(
                    "Claude Code token cannot read usage (no user:profile scope). Run claude to sign in again."
                  )
                  .asLeft[ClaudeOAuth]
            }
          }
      })
}

object ClaudeCredentials {

  val Service: String = "Claude Code-credentials"

  /** The first read may have the "Always Allow" dialog open, later reads must be quick. */
  def timeoutFor(attempt: ReadAttempt): FiniteDuration = attempt match {
    case ReadAttempt.First => 15.seconds
    case ReadAttempt.Subsequent => 3.seconds
  }
}
