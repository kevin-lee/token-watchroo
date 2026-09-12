package tokenwatchroo.providers

import cats.effect.IO
import cats.syntax.all.*
import scala.concurrent.duration.FiniteDuration

/** Reads generic passwords from the login keychain. */
trait KeychainReader {
  def readGenericPassword(service: String, timeout: FiniteDuration): IO[Either[ProviderError, String]]
}

/** Shells out to `/usr/bin/security find-generic-password -s <service> -w`.
  *
  * Why the CLI and not `SecItemCopyMatching`: the "Claude Code-credentials" item's ACL whitelists `/usr/bin/security`,
  * so this path is silent after one "Always Allow", while a direct read from a third-party binary prompts on every
  * access (TokenEater `SecurityCLIReader.swift`). The timeout guards against a child that blocks on the keychain
  * authorisation dialog.
  */
object SecurityCli extends KeychainReader {

  val Executable: String = "/usr/bin/security"

  private val ExitNotFound     = 44
  private val ExitAccessDenied = 45

  override def readGenericPassword(service: String, timeout: FiniteDuration): IO[Either[ProviderError, String]] =
    Processes
      .run(List(Executable, "find-generic-password", "-s", service, "-w"), timeout)
      .map(_.flatMap { output =>
        output.exitCode match {
          case 0 => output.stdout.trim.asRight[ProviderError]
          case ExitNotFound => ProviderError.credentialsMissing.asLeft[String]
          case ExitAccessDenied => ProviderError.credentialsAccessDenied.asLeft[String]
          case other =>
            ProviderError.credentialsMalformed(s"security exited with $other: ${output.stdout.trim}").asLeft[String]
        }
      })
}
