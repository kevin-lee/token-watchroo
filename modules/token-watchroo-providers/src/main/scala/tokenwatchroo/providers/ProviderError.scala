package tokenwatchroo.providers

import cats.{Eq, Show}
import cats.derived.*
import tokenwatchroo.core.*
import tokenwatchroo.core.providers.CredentialProblem

/** Why a usage read for a Claude Code card is not supported (#10). */
enum UnsupportedReason derives CanEqual, Eq, Show {

  /** The token has no `user:profile` scope. */
  case NoProfileScope

  /** No keychain item for the config folder in the settings (#91). */
  case ConfigDirNoSignIn

  /** A readable response with no window and no spend limit. */
  case NoUsageLimit
}

enum ProviderError derives CanEqual, Eq, Show {
  case CredentialsMissing
  case CredentialsAccessDenied
  case CredentialsMalformed(problem: CredentialProblem)
  case TokenExpired(status: HttpStatus)
  case RateLimited
  case Http(status: HttpStatus)
  case Network(message: String)
  case Decode(message: String)
  case Timeout
  case Unsupported(reason: UnsupportedReason)
}

object ProviderError {
  def credentialsMissing: ProviderError                               = ProviderError.CredentialsMissing
  def credentialsAccessDenied: ProviderError                          = ProviderError.CredentialsAccessDenied
  def credentialsMalformed(problem: CredentialProblem): ProviderError = ProviderError.CredentialsMalformed(problem)
  def tokenExpired(status: HttpStatus): ProviderError                 = ProviderError.TokenExpired(status)
  def rateLimited: ProviderError                                      = ProviderError.RateLimited
  def http(status: HttpStatus): ProviderError                         = ProviderError.Http(status)
  def network(message: String): ProviderError                         = ProviderError.Network(message)
  def decode(message: String): ProviderError                          = ProviderError.Decode(message)
  def timeout: ProviderError                                          = ProviderError.Timeout
  def unsupported(reason: UnsupportedReason): ProviderError           = ProviderError.Unsupported(reason)

  /** A readable response with no window and no spend limit, so the card is unavailable instead of OK at 0%. */
  def noUsageLimit: ProviderError = ProviderError.Unsupported(UnsupportedReason.NoUsageLimit)

  extension (error: ProviderError) {

    /** The text shown on an unavailable card, in the language of `messages` (#10). `cli` is the command the user runs
      * to sign in.
      */
    def message(cli: String, messages: Messages): String = error match {
      case ProviderError.CredentialsMissing => messages.errorNotSignedIn(cli)
      case ProviderError.CredentialsAccessDenied => messages.errorAccessDenied
      case ProviderError.CredentialsMalformed(problem) => messages.credentialProblem(problem)
      case ProviderError.TokenExpired(status) => messages.errorTokenExpired(status.value, cli)
      case ProviderError.RateLimited => messages.errorRateLimited
      case ProviderError.Http(status) => messages.errorHttp(status.value)
      case ProviderError.Network(message) => messages.errorNetwork(message)
      case ProviderError.Decode(message) => messages.errorDecode(message)
      case ProviderError.Timeout => messages.errorTimeout
      case ProviderError.Unsupported(UnsupportedReason.NoProfileScope) => messages.credentialsNoProfileScope
      case ProviderError.Unsupported(UnsupportedReason.ConfigDirNoSignIn) => messages.credentialsConfigDirNoSignIn
      case ProviderError.Unsupported(UnsupportedReason.NoUsageLimit) => messages.errorNoUsageLimit
    }

    def toErrorMessage(cli: String, messages: Messages): ErrorMessage =
      ErrorMessage
        .fromString(error.message(cli, messages))
        .getOrElse(ErrorMessage.fromString(messages.errorUnknown).getOrElse(Unknown))
  }

  private val Unknown: ErrorMessage = ErrorMessage(refined4s.types.all.NonEmptyString("Unknown error"))
}
