package tokenwatchroo.providers

import tokenwatchroo.core.*

/** The bundles are embedded in the linked test binary (#10), as they are in the static library. */
class MessageResourcesSpec extends munit.FunSuite {

  test("load reads both bundles from the binary") {
    MessageResources.load match {
      case Left(reason) => fail(reason)
      case Right(bundles) =>
        assertEquals(bundles.of(AppLanguage.En).errorTimeout, "Timed out reading credentials")
        assertEquals(bundles.of(AppLanguage.Ko).errorTimeout, "인증 정보를 읽는 중 시간이 초과되었습니다")
    }
  }

  test("load gives the same bundles as the core test fixture") {
    assertEquals(MessageResources.load, Right(TestMessages.bundles))
  }
}
