package tokenwatchroo.providers

import cats.{Eq, Show}
import cats.derived.*
import tokenwatchroo.core.*

enum ProviderError derives CanEqual, Eq, Show {
  case CredentialsMissing
  case CredentialsAccessDenied
  case CredentialsMalformed(message: String)
  case TokenExpired(status: HttpStatus)
  case RateLimited
  case Http(status: HttpStatus)
  case Network(message: String)
  case Decode(message: String)
  case Timeout
  case Unsupported(message: String)
}

object ProviderError {
  def credentialsMissing: ProviderError                    = ProviderError.CredentialsMissing
  def credentialsAccessDenied: ProviderError               = ProviderError.CredentialsAccessDenied
  def credentialsMalformed(message: String): ProviderError = ProviderError.CredentialsMalformed(message)
  def tokenExpired(status: HttpStatus): ProviderError      = ProviderError.TokenExpired(status)
  def rateLimited: ProviderError                           = ProviderError.RateLimited
  def http(status: HttpStatus): ProviderError              = ProviderError.Http(status)
  def network(message: String): ProviderError              = ProviderError.Network(message)
  def decode(message: String): ProviderError               = ProviderError.Decode(message)
  def timeout: ProviderError                               = ProviderError.Timeout
  def unsupported(message: String): ProviderError          = ProviderError.Unsupported(message)

  /** A readable response with no window and no spend limit, so the card is unavailable instead of OK at 0%. */
  def noUsageLimit: ProviderError = ProviderError.Unsupported("No usage limit reported by the usage API")

  extension (error: ProviderError) {

    /** The text shown on an unavailable card. `cli` is the command the user runs to sign in. */
    def message(cli: String): String = error match {
      case ProviderError.CredentialsMissing => s"Not signed in. Run $cli once."
      case ProviderError.CredentialsAccessDenied => "Keychain access denied. Click Always Allow when macOS asks."
      case ProviderError.CredentialsMalformed(message) => message
      case ProviderError.TokenExpired(status) => s"Token expired (HTTP ${status.value}). Run $cli to sign in again."
      case ProviderError.RateLimited => "Usage API rate limited. Retrying next refresh."
      case ProviderError.Http(status) => s"Usage API error HTTP ${status.value}"
      case ProviderError.Network(message) => s"Network error: $message"
      case ProviderError.Decode(message) => s"Unexpected response: $message"
      case ProviderError.Timeout => "Timed out reading credentials"
      case ProviderError.Unsupported(message) => message
    }

    def toErrorMessage(cli: String): ErrorMessage =
      ErrorMessage.fromString(error.message(cli)).getOrElse(ErrorMessage.fromString("Unknown error").getOrElse(Unknown))
  }

  private val Unknown: ErrorMessage = ErrorMessage(refined4s.types.all.NonEmptyString("Unknown error"))
}
