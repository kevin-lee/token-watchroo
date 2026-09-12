package tokenwatchroo.providers

import cats.effect.unsafe.implicits.global
import refined4s.types.all.*
import tokenwatchroo.core.*

/** Network smoke test, only when `TW_NETWORK_TESTS=1`. */
class CurlHttpSpec extends munit.FunSuite {

  private val enabled = Option(System.getenv("TW_NETWORK_TESTS")).contains("1")

  test("GET https://example.com returns 200 and a body") {
    assume(enabled, "set TW_NETWORK_TESTS=1 to run network tests")
    val result = (CurlHttp.globalInit *> CurlHttp.get(
      "https://example.com/",
      List("Accept" -> "text/html"),
      UserAgent(NonEmptyString("token-watchroo-test/0")),
    )).unsafeRunSync()
    assertEquals(result.map(_.status.value), Right(200))
    assert(result.exists(_.body.contains("Example Domain")))
  }
}
