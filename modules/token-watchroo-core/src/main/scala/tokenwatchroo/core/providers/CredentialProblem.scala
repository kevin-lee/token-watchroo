package tokenwatchroo.core.providers

import cats.{Eq, Show}
import cats.derived.*

/** Why the credentials of an agent cannot be used (#10). The card text comes from `Messages.credentialProblem`, so it
  * follows the language of the shell. The `String` fields hold text from another system (a JSON decoder, an
  * exception, the `security` tool) or a file name, and are shown as they are.
  */
enum CredentialProblem derives CanEqual, Eq, Show {

  /** The keychain item has only MCP state. */
  case ClaudeNoSignIn

  /** Claude Code emptied the refresh token or ended the sign-in (#94). */
  case ClaudeSignInExpired
  case ClaudeNoAccessToken
  case ClaudeTokenExpired
  case CodexApiKeyMode
  case CodexNoAccessToken

  /** The credentials JSON cannot be decoded. */
  case Unreadable(detail: String)

  /** The config folder from the settings cannot be converted to a keychain service (#91). */
  case ConfigDirUnreadable(reason: String)
  case CodexHomeMissing
  case FileUnreadable(file: String, detail: String)
  case SecurityFailed(code: Int, output: String)
  case ProcessFailed(command: String, detail: String)
}

object CredentialProblem {
  def claudeNoSignIn: CredentialProblem                                 = CredentialProblem.ClaudeNoSignIn
  def claudeSignInExpired: CredentialProblem                            = CredentialProblem.ClaudeSignInExpired
  def claudeNoAccessToken: CredentialProblem                            = CredentialProblem.ClaudeNoAccessToken
  def claudeTokenExpired: CredentialProblem                             = CredentialProblem.ClaudeTokenExpired
  def codexApiKeyMode: CredentialProblem                                = CredentialProblem.CodexApiKeyMode
  def codexNoAccessToken: CredentialProblem                             = CredentialProblem.CodexNoAccessToken
  def unreadable(detail: String): CredentialProblem                     = CredentialProblem.Unreadable(detail)
  def configDirUnreadable(reason: String): CredentialProblem            = CredentialProblem.ConfigDirUnreadable(reason)
  def codexHomeMissing: CredentialProblem                               = CredentialProblem.CodexHomeMissing
  def fileUnreadable(file: String, detail: String): CredentialProblem   =
    CredentialProblem.FileUnreadable(file, detail)
  def securityFailed(code: Int, output: String): CredentialProblem      = CredentialProblem.SecurityFailed(code, output)
  def processFailed(command: String, detail: String): CredentialProblem =
    CredentialProblem.ProcessFailed(command, detail)
}
