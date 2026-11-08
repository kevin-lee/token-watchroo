package tokenwatchroo.providers

import cats.effect.IO
import cats.syntax.all.*
import scala.concurrent.duration.*
import tokenwatchroo.core.*
import tokenwatchroo.core.providers.{ClaudeCredentialsBlob, ClaudeOAuth}

trait ClaudeCredentialReader {
  def read(
    attempt: ReadAttempt,
    nowMillis: Long,
    configDir: Option[ClaudeConfigDir],
  ): IO[Either[ProviderError, ClaudeOAuth]]
}

/** The Claude Code OAuth credentials from the keychain.
  *
  * Without a config folder the item is `Claude Code-credentials`. With a config folder from the settings (#91) the item
  * is `ClaudeKeychainService.forConfigDir`, and when it is missing, the same folder with its trailing slash toggled.
  * The plain item is never read when a folder is set, so a wrong folder never shows another profile.
  *
  * For each service, the item that Claude Code uses has the account from `KeychainAccount.claudeCode`. Older Claude
  * Code versions or other tools can leave more items with the same service (#90), and `security` without `-a` returns
  * any of them. The read with no account is only for a keychain that has no item with that account.
  */
final class ClaudeCredentials(keychain: KeychainReader, account: KeychainAccount) extends ClaudeCredentialReader {

  private def readService(service: String, timeout: FiniteDuration): IO[Either[ProviderError, String]] =
    keychain
      .readGenericPassword(service, account.some, timeout)
      .flatMap {
        case Left(ProviderError.CredentialsMissing) =>
          keychain.readGenericPassword(service, none[KeychainAccount], timeout)
        case other => IO.pure(other)
      }

  /** The first result that is not `CredentialsMissing`, in the order of `services`. */
  private def readFirst(services: List[String], timeout: FiniteDuration): IO[Either[ProviderError, String]] =
    services match {
      case Nil => IO.pure(ProviderError.credentialsMissing.asLeft[String])
      case service :: rest =>
        readService(service, timeout).flatMap {
          case Left(ProviderError.CredentialsMissing) => readFirst(rest, timeout)
          case other => IO.pure(other)
        }
    }

  private def readRaw(configDir: Option[ClaudeConfigDir], timeout: FiniteDuration): IO[Either[ProviderError, String]] =
    configDir match {
      case None => readService(ClaudeCredentials.Service, timeout)
      case Some(dir) =>
        ClaudeKeychainService.candidates(dir) match {
          case Left(reason) =>
            IO.pure(
              ProviderError.credentialsMalformed(s"Config folder in Settings cannot be read: $reason").asLeft[String]
            )
          case Right(services) =>
            readFirst(services, timeout).map {
              case Left(ProviderError.CredentialsMissing) =>
                ProviderError.unsupported(ClaudeCredentials.ConfigDirMissingMessage).asLeft[String]
              case other => other
            }
        }
    }

  override def read(
    attempt: ReadAttempt,
    nowMillis: Long,
    configDir: Option[ClaudeConfigDir],
  ): IO[Either[ProviderError, ClaudeOAuth]] =
    readRaw(configDir, ClaudeCredentials.timeoutFor(attempt))
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
                    "Claude Code token cannot read usage (no user:profile scope). Run claude and use /login to sign in again."
                  )
                  .asLeft[ClaudeOAuth]
            }
          }
      })
}

object ClaudeCredentials {

  val Service: String = "Claude Code-credentials"

  /** The card text when no item exists for the config folder in the settings (#91). */
  val ConfigDirMissingMessage: String =
    "No Claude Code sign-in for the Config folder in Settings. Check the folder or use /login."

  /** The first read may have the "Always Allow" dialog open, later reads must be quick. */
  def timeoutFor(attempt: ReadAttempt): FiniteDuration = attempt match {
    case ReadAttempt.First => 15.seconds
    case ReadAttempt.Subsequent => 3.seconds
  }
}
