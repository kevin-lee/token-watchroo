package tokenwatchroo.providers

import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import tokenwatchroo.core.*
import tokenwatchroo.core.providers.ClaudeOAuth

/** The item with the Claude Code account wins, and the read with no account is only for no such item (#90). */
class ClaudeCredentialsSpec extends munit.FunSuite {

  private val nowMillis = 1789185600000L

  private val S    = ClaudeCredentials.Service
  private val alex = Fakes.keychainAccount
  private val t    = ClaudeCredentials.timeoutFor(ReadAttempt.First)

  private def read(
    items: Map[Option[KeychainAccount], Either[ProviderError, String]],
    attempt: ReadAttempt = ReadAttempt.First,
  ): (Either[ProviderError, ClaudeOAuth], List[Fakes.KeychainRead]) =
    (for {
      keychain <- Fakes.KeyedKeychain.make(items)
      result   <- new ClaudeCredentials(keychain, alex).read(attempt, nowMillis)
      calls    <- keychain.calls
    } yield (result, calls)).unsafeRunSync()

  test("the item with the account wins over an MCP-only item with another account") {
    val (result, calls) = read(Map(alex.some -> Fakes.claudeBlob.asRight, none -> Fakes.claudeMcpOnlyBlob.asRight))
    assert(result.isRight, result)
    assertEquals(calls, List(Fakes.KeychainRead(S, alex.some, t)))
  }

  test("no item with the account falls back to the read with no account") {
    val (result, calls) = read(Map(none -> Fakes.claudeBlob.asRight))
    assert(result.isRight, result)
    assertEquals(calls, List(Fakes.KeychainRead(S, alex.some, t), Fakes.KeychainRead(S, none, t)))
  }

  test("an MCP-only item with the account does not fall back") {
    val (result, calls) = read(Map(alex.some -> Fakes.claudeMcpOnlyBlob.asRight, none -> Fakes.claudeBlob.asRight))
    assertEquals(
      result,
      ProviderError
        .credentialsMalformed(
          "Keychain item has no Claude sign-in (only MCP state). Run claude and use /login to sign in."
        )
        .asLeft[ClaudeOAuth],
    )
    assertEquals(calls, List(Fakes.KeychainRead(S, alex.some, t)))
  }

  test("an expired sign-in for the account does not fall back") {
    val (result, calls) = read(Map(alex.some -> Fakes.claudeExpiredBlob.asRight, none -> Fakes.claudeBlob.asRight))
    assertEquals(
      result,
      ProviderError
        .credentialsMalformed("Claude Code sign-in expired. Run claude and use /login to sign in again.")
        .asLeft[ClaudeOAuth],
    )
    assertEquals(calls, List(Fakes.KeychainRead(S, alex.some, t)))
  }

  test("access denied for the account does not fall back") {
    val (result, calls) =
      read(Map(alex.some -> ProviderError.credentialsAccessDenied.asLeft, none -> Fakes.claudeBlob.asRight))
    assertEquals(result, ProviderError.credentialsAccessDenied.asLeft[ClaudeOAuth])
    assertEquals(calls, List(Fakes.KeychainRead(S, alex.some, t)))
  }

  test("no item at all is CredentialsMissing") {
    val (result, calls) = read(Map.empty)
    assertEquals(result, ProviderError.credentialsMissing.asLeft[ClaudeOAuth])
    assertEquals(calls, List(Fakes.KeychainRead(S, alex.some, t), Fakes.KeychainRead(S, none, t)))
  }

  test("a subsequent read uses the short timeout for both reads") {
    val u          = ClaudeCredentials.timeoutFor(ReadAttempt.Subsequent)
    val (_, calls) = read(Map(none -> Fakes.claudeBlob.asRight), ReadAttempt.Subsequent)
    assertEquals(calls, List(Fakes.KeychainRead(S, alex.some, u), Fakes.KeychainRead(S, none, u)))
  }
}
