package tokenwatchroo.providers

import cats.effect.unsafe.implicits.global
import java.nio.file.{Files, Paths}
import scala.concurrent.duration.*

class SecurityCliSpec extends munit.FunSuite {

  test("an unknown service is CredentialsMissing") {
    assume(Files.exists(Paths.get(SecurityCli.Executable)), s"${SecurityCli.Executable} is not available")
    val result = SecurityCli.readGenericPassword("token-watchroo-no-such-service-2f0c", 5.seconds).unsafeRunSync()
    assertEquals(result, Left(ProviderError.CredentialsMissing))
  }

  test("a missing executable is reported, not thrown") {
    val result = Processes.run(List("/nonexistent/binary/for/token-watchroo", "--version"), 1.second).unsafeRunSync()
    assert(result.isLeft)
  }
}
