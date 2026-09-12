package tokenwatchroo.providers

import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import refined4s.types.all.*
import tokenwatchroo.core.*

class CodexAuthSpec extends munit.FunSuite {

  private def home(authJson: Option[String]): CodexHome = {
    val dir = Files.createTempDirectory("tw-codex-home")
    authJson.foreach(json => Files.write(dir.resolve(CodexAuth.FileName), json.getBytes(StandardCharsets.UTF_8)))
    CodexHome(NonEmptyString.unsafeFrom(dir.toString))
  }

  test("a valid auth.json gives token and account id") {
    val result = CodexAuth.read(home(Fakes.codexAuth.some)).unsafeRunSync()
    assertEquals(result.map(_.accessToken.value.value), Right("eyJ-access"))
    assertEquals(result.map(_.accountId.map(_.value.value)), Right(Some("acct-1")))
  }

  test("a missing auth.json is CredentialsMissing") {
    assertEquals(CodexAuth.read(home(none[String])).unsafeRunSync(), Left(ProviderError.CredentialsMissing))
  }

  test("API-key mode is reported with its message") {
    val apiKeyOnly = """{"auth_mode":"apikey","OPENAI_API_KEY":"sk-proj-x","tokens":null}"""
    assertEquals(
      CodexAuth.read(home(apiKeyOnly.some)).unsafeRunSync(),
      Left(ProviderError.CredentialsMalformed("Codex is in API-key mode, not supported in v1.")),
    )
  }

  test("garbage is CredentialsMalformed") {
    assert(CodexAuth.read(home("not json".some)).unsafeRunSync().isLeft)
  }
}
