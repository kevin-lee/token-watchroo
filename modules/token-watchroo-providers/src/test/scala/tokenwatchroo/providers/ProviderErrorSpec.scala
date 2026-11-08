package tokenwatchroo.providers

import tokenwatchroo.core.*
import tokenwatchroo.core.providers.CredentialProblem

/** The card text of every `ProviderError` in both languages (#10). */
class ProviderErrorSpec extends munit.FunSuite {

  private val cases: List[(ProviderError, String, String)] = List(
    (ProviderError.CredentialsMissing, "Not signed in. Run claude once.", "로그인되어 있지 않습니다. claude 명령을 한 번 실행하세요."),
    (
      ProviderError.CredentialsAccessDenied,
      "Keychain access denied. Click Always Allow when macOS asks.",
      "키체인 접근이 거부되었습니다. macOS가 물으면 '항상 허용'을 클릭하세요.",
    ),
    (
      ProviderError.CredentialsMalformed(CredentialProblem.Unreadable("x")),
      "Credentials unreadable: x",
      "인증 정보를 읽을 수 없습니다: x",
    ),
    (
      ProviderError.CredentialsMalformed(CredentialProblem.ClaudeSignInExpired),
      "Claude Code sign-in expired. Run claude and use /login to sign in again.",
      "Claude Code 로그인이 만료되었습니다. claude를 실행하고 /login으로 다시 로그인하세요.",
    ),
    (
      ProviderError.CredentialsMalformed(CredentialProblem.SecurityFailed(1, "out")),
      "security exited with 1: out",
      "security 명령이 1 코드로 종료되었습니다: out",
    ),
    (
      ProviderError.TokenExpired(HttpStatus(401)),
      "Token expired (HTTP 401). Run claude to sign in again.",
      "토큰이 만료되었습니다(HTTP 401). claude 명령으로 다시 로그인하세요.",
    ),
    (
      ProviderError.RateLimited,
      "Usage API rate limited. Retrying next refresh.",
      "사용량 API 요청 한도에 걸렸습니다. 다음 새로 고침에 다시 시도합니다.",
    ),
    (ProviderError.Http(HttpStatus(500)), "Usage API error HTTP 500", "사용량 API 오류 HTTP 500"),
    (ProviderError.Network("curl 6"), "Network error: curl 6", "네트워크 오류: curl 6"),
    (ProviderError.Decode("bad"), "Unexpected response: bad", "예상하지 못한 응답: bad"),
    (ProviderError.Timeout, "Timed out reading credentials", "인증 정보를 읽는 중 시간이 초과되었습니다"),
    (
      ProviderError.Unsupported(UnsupportedReason.NoProfileScope),
      "Claude Code token cannot read usage (no user:profile scope). Run claude and use /login to sign in again.",
      "Claude Code 토큰으로 사용량을 읽을 수 없습니다(user:profile 범위 없음). claude를 실행하고 /login으로 다시 로그인하세요.",
    ),
    (
      ProviderError.Unsupported(UnsupportedReason.ConfigDirNoSignIn),
      "No Claude Code sign-in for the Config folder in Settings. Check the folder or use /login.",
      "설정의 구성 폴더에 대한 Claude Code 로그인이 없습니다. 폴더를 확인하거나 /login을 사용하세요.",
    ),
    (
      ProviderError.noUsageLimit,
      "No usage limit reported by the usage API",
      "사용량 API가 사용량 한도를 보고하지 않았습니다",
    ),
  )

  cases.foreach {
    case (error, english, korean) =>
      test(s"$error in English and Korean") {
        assertEquals(error.message("claude", TestMessages.en), english)
        assertEquals(error.message("claude", TestMessages.ko), korean)
        assertEquals(error.toErrorMessage("claude", TestMessages.ko).value.value, korean)
      }
  }
}
