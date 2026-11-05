package tokenwatchroo.providers

import cats.effect.IO
import cats.syntax.all.*
import scala.concurrent.duration.*
import tokenwatchroo.core.*
import tokenwatchroo.core.providers.{ClaudeCredentialsBlob, ClaudeOAuth}

trait ClaudeCredentialReader {
  def read(attempt: ReadAttempt, nowMillis: Long): IO[Either[ProviderError, ClaudeOAuth]]
}

/** The Claude Code OAuth credentials from the keychain item `Claude Code-credentials`.
  *
  * The item that Claude Code uses has the account from `KeychainAccount.claudeCode`. Older Claude Code versions or
  * other tools can leave more items with the same service (#90), and `security` without `-a` returns any of them. The
  * read with no account is only for a keychain that has no item with that account.
  */
final class ClaudeCredentials(keychain: KeychainReader, account: KeychainAccount) extends ClaudeCredentialReader {

  private def readRaw(timeout: FiniteDuration): IO[Either[ProviderError, String]] =
    keychain
      .readGenericPassword(ClaudeCredentials.Service, account.some, timeout)
      .flatMap {
        case Left(ProviderError.CredentialsMissing) =>
          keychain.readGenericPassword(ClaudeCredentials.Service, none[KeychainAccount], timeout)
        case other => IO.pure(other)
      }

  override def read(attempt: ReadAttempt, nowMillis: Long): IO[Either[ProviderError, ClaudeOAuth]] =
    readRaw(ClaudeCredentials.timeoutFor(attempt))
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
