package tokenwatchroo.providers

import cats.effect.IO
import cats.syntax.all.*
import tokenwatchroo.core.*
import tokenwatchroo.core.providers.ClaudeOAuth

/** Debug only, for testing a sign-out without signing out. While the file named by `TW_DEBUG_CLAUDE_SIGNED_OUT_FILE`
  * holds `signed-out` (trimmed), every read returns `CredentialsMissing` without touching the keychain. Any other
  * content, a missing file, or an unreadable file passes the read through. The file is read on every read, so the
  * switch flips while the app runs.
  */
final class SimulatedClaudeSignOut(underlying: ClaudeCredentialReader, flagFile: os.Path)
    extends ClaudeCredentialReader {

  override def read(attempt: ReadAttempt, nowMillis: Long): IO[Either[ProviderError, ClaudeOAuth]] =
    SimulatedClaudeSignOut
      .isSignedOut(flagFile)
      .ifM(IO.pure(ProviderError.credentialsMissing.asLeft[ClaudeOAuth]), underlying.read(attempt, nowMillis))
}

object SimulatedClaudeSignOut {

  val EnvVar: String = "TW_DEBUG_CLAUDE_SIGNED_OUT_FILE"

  val SignedOut: String = "signed-out"

  def isSignedOut(flagFile: os.Path): IO[Boolean] =
    IO.blocking(os.exists(flagFile) && os.read(flagFile).trim === SignedOut).handleError(_ => false)

  /** The reader itself unless the variable holds an absolute path. */
  def wrap(reader: ClaudeCredentialReader, env: Env): ClaudeCredentialReader =
    env.get(EnvVar).flatMap(value => Either.catchNonFatal(os.Path(value)).toOption) match {
      case Some(flagFile) => new SimulatedClaudeSignOut(reader, flagFile)
      case None => reader
    }
}
