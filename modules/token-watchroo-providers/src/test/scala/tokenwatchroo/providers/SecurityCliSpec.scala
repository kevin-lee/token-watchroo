package tokenwatchroo.providers

import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.nio.file.{Files, Paths}
import refined4s.types.all.*
import scala.concurrent.duration.*
import tokenwatchroo.core.*

class SecurityCliSpec extends munit.FunSuite {

  test("an unknown service is CredentialsMissing") {
    assume(Files.exists(Paths.get(SecurityCli.Executable)), s"${SecurityCli.Executable} is not available")
    val result =
      SecurityCli
        .readGenericPassword("token-watchroo-no-such-service-2f0c", none[KeychainAccount], 5.seconds)
        .unsafeRunSync()
    assertEquals(result, Left(ProviderError.CredentialsMissing))
  }

  test("an unknown service with an account is CredentialsMissing") {
    assume(Files.exists(Paths.get(SecurityCli.Executable)), s"${SecurityCli.Executable} is not available")
    val account = KeychainAccount(NonEmptyString("token-watchroo-no-such-account-2f0c"))
    val result  =
      SecurityCli.readGenericPassword("token-watchroo-no-such-service-2f0c", account.some, 5.seconds).unsafeRunSync()
    assertEquals(result, Left(ProviderError.CredentialsMissing))
  }

  test("a missing executable is reported, not thrown") {
    val result = Processes.run(List("/nonexistent/binary/for/token-watchroo", "--version"), 1.second).unsafeRunSync()
    assert(result.isLeft)
  }
}
