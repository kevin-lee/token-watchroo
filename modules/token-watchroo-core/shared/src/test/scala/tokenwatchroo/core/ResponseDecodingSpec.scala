package tokenwatchroo.core

import cats.syntax.all.*
import hedgehog.*
import hedgehog.runner.*
import tokenwatchroo.core.codecs.given
import tokenwatchroo.core.providers.*

object ResponseDecodingSpec extends Properties {

  override def tests: List[Test] = List(
    example("Claude usage response becomes session and weekly windows", testClaudeUsage),
    example("Claude usage response with missing limits gives idle windows", testClaudeUsageIdle),
    example("Claude keychain blob gives a token, scope, and plan label", testClaudeBlob),
    example("Claude keychain blob with an expired token is rejected", testClaudeBlobExpired),
    example("Claude keychain blob without OAuth is rejected", testClaudeBlobMcpOnly),
    example("Claude keychain blob with a rate limit tier gives the multiplier", testClaudeBlobWithTier),
    example(
      "Claude keychain blob with an unknown subscription type keeps the capitalised label",
      testClaudeBlobUnknownSubscription,
    ),
    example("Claude profile response gives the plan from the organization type and tier", testClaudeProfile),
    example("Claude profile response with a team seat gives Team Premium", testClaudeProfileTeamSeat),
    example("Claude profile response prefers the organization over the account flags", testClaudeProfileOrgWins),
    example("Claude profile response without an organization falls back to the account flags", testClaudeProfileFlags),
    example("Claude profile response with missing or unknown fields gives no plan", testClaudeProfileUnknown),
    example("Codex usage response becomes session and weekly windows", testCodexUsage),
    example("Codex auth file gives token and account id", testCodexAuth),
    example("Codex auth file in API-key mode is rejected", testCodexAuthApiKey),
  )

  private val claudeUsage =
    """{"five_hour":{"utilization":42.5,"resets_at":"2026-05-11T18:00:00Z"},"seven_day":{"utilization":17.0,"resets_at":"2026-05-18T00:00:00+00:00"},"seven_day_opus":{"utilization":0,"resets_at":null},"seven_day_sonnet":{"utilization":25.0,"resets_at":"2026-05-18T00:00:00Z"},"extra_usage":{"is_enabled":false}}"""

  def testClaudeUsage: Result = {
    val windows =
      codecs.readEither[ClaudeUsageResponse](claudeUsage).leftMap(_.message).flatMap(_.toWindows.leftMap(_.message))
    Result.all(
      List(
        windows.map(_.map(_.id)) ==== Right(List(WindowId.Session, WindowId.Weekly)),
        windows.map(_.map(_.usedPercent)) ==== Right(List(UsedPercent.clamp(42.5d), UsedPercent.clamp(17.0d))),
        windows.map(_.flatMap(_.resetsAt)) ==== Right(
          List(Iso8601.parseToEpochSeconds("2026-05-11T18:00:00Z"), Iso8601.parseToEpochSeconds("2026-05-18T00:00:00Z"))
            .flatMap(_.toOption)
        ),
        windows.map(_.flatMap(_.windowLength)) ==== Right(List(Seconds(18000L), Seconds(604800L))),
      )
    )
  }

  def testClaudeUsageIdle: Result = {
    val windows = codecs
      .readEither[ClaudeUsageResponse]("""{"five_hour":null}""")
      .leftMap(_.message)
      .flatMap(_.toWindows.leftMap(_.message))
    windows.map(_.map(w => (w.isIdle, w.usedPercent))) ==== Right(
      List((true, UsedPercent.clamp(0.0d)), (true, UsedPercent.clamp(0.0d)))
    )
  }

  private val blob =
    """{"claudeAiOauth":{"accessToken":"sk-ant-oat01-example","refreshToken":"sk-ant-ort01-example","expiresAt":4102444800000,"scopes":["user:inference","user:profile"],"subscriptionType":"max"},"mcpOAuth":{}}"""

  def testClaudeBlob: Result = {
    val parsed = ClaudeCredentialsBlob.parse(blob, 1789185600000L)
    Result.all(
      List(
        parsed.map(_.profileScope) ==== Right(ProfileScope.Granted),
        parsed.map(_.planLabel.map(_.value.value)) ==== Right(Some("Max")),
        parsed.map(_.accessToken.value.value) ==== Right("sk-ant-oat01-example"),
        parsed.map(_.accessToken.toString) ==== Right("sk-ant-oat01-example"),
        parsed.map(a => cats.Show[AccessToken].show(a.accessToken)) ==== Right("<redacted>"),
      )
    )
  }

  def testClaudeBlobExpired: Result =
    ClaudeCredentialsBlob.parse(blob, 4102444800001L) ==== Left(
      "Claude Code token expired. Run claude to sign in again."
    )

  def testClaudeBlobMcpOnly: Result =
    ClaudeCredentialsBlob.parse("""{"mcpOAuth":{"server":{}}}""", 0L).isLeft ==== true

  private val blobWithTier =
    """{"claudeAiOauth":{"accessToken":"sk-ant-oat01-example","expiresAt":4102444800000,"scopes":["user:profile"],"subscriptionType":"max","rateLimitTier":"default_claude_max_5x"}}"""

  def testClaudeBlobWithTier: Result =
    ClaudeCredentialsBlob.parse(blobWithTier, 1789185600000L).map(_.planLabel.map(_.value.value)) ==== Right(
      Some("Max 5x")
    )

  def testClaudeBlobUnknownSubscription: Result = {
    val blobFree =
      """{"claudeAiOauth":{"accessToken":"sk-ant-oat01-example","expiresAt":4102444800000,"scopes":["user:profile"],"subscriptionType":"free","rateLimitTier":"default_claude_max_5x"}}"""
    ClaudeCredentialsBlob.parse(blobFree, 1789185600000L).map(_.planLabel.map(_.value.value)) ==== Right(Some("Free"))
  }

  private val claudeProfile =
    """{"account":{"uuid":"a","full_name":"Test","display_name":"Test","email":"test@example.com","has_claude_max":true,"has_claude_pro":false,"created_at":"2024-03-12T02:08:34.586213Z"},"organization":{"uuid":"o","name":"Org","organization_type":"claude_max","billing_type":"stripe_subscription","rate_limit_tier":"default_claude_max_5x","seat_tier":null,"has_extra_usage_enabled":false,"subscription_status":"active","cc_onboarding_flags":{}},"application":{"uuid":"p","name":"Claude Code","slug":"claude-code"},"enabled_plugins":[]}"""

  private def profileLabel(json: String): Either[String, Option[String]] =
    codecs.readEither[ClaudeProfileResponse](json).leftMap(_.message).map(_.planLabel.map(_.value.value))

  def testClaudeProfile: Result = profileLabel(claudeProfile) ==== Right(Some("Max 5x"))

  def testClaudeProfileTeamSeat: Result =
    profileLabel(
      """{"organization":{"organization_type":"claude_team","rate_limit_tier":"default_claude_team_5x","seat_tier":"team_tier_1"}}"""
    ) ==== Right(Some("Team Premium"))

  def testClaudeProfileOrgWins: Result =
    profileLabel(
      """{"account":{"has_claude_max":false,"has_claude_pro":true},"organization":{"organization_type":"claude_team"}}"""
    ) ==== Right(Some("Team"))

  def testClaudeProfileFlags: Result =
    profileLabel("""{"account":{"has_claude_max":false,"has_claude_pro":true}}""") ==== Right(Some("Pro"))

  def testClaudeProfileUnknown: Result =
    Result.all(
      List(
        profileLabel("{}") ==== Right(None),
        profileLabel("""{"account":null,"organization":null}""") ==== Right(None),
        profileLabel(
          """{"account":{"has_claude_max":false,"has_claude_pro":false},"organization":{"organization_type":"claude_future","rate_limit_tier":"default_claude_ai","seat_tier":null}}"""
        ) ==== Right(None),
        profileLabel("""{"account":{"has_claude_max":"yes"}}""").isLeft ==== true,
      )
    )

  private val codexUsage =
    """{"plan_type":"plus","rate_limit":{"primary_window":{"used_percent":33,"limit_window_seconds":18000,"resets_at":1789187040},"secondary_window":{"used_percent":12.0,"limit_window_seconds":604800,"resets_at":1789617600}},"credits":{"balance":"0"}}"""

  def testCodexUsage: Result = {
    val parsed = codecs.readEither[CodexUsageResponse](codexUsage)
    Result.all(
      List(
        parsed.map(_.toWindows.map(_.usedPercent)) ==== Right(List(UsedPercent.clamp(33.0d), UsedPercent.clamp(12.0d))),
        parsed.map(_.toWindows.flatMap(_.resetsAt)) ==== Right(
          List(EpochSeconds(1789187040L), EpochSeconds(1789617600L))
        ),
        parsed.map(_.toWindows.flatMap(_.windowLength)) ==== Right(List(Seconds(18000L), Seconds(604800L))),
        parsed.map(_.planLabel.map(_.value.value)) ==== Right(Some("Plus")),
      )
    )
  }

  private val codexAuth =
    """{"auth_mode":"chatgpt","OPENAI_API_KEY":null,"tokens":{"id_token":"x.y.z","access_token":"eyJ-access","refresh_token":"r","account_id":"acct-123"},"last_refresh":"2026-09-10T00:00:00Z"}"""

  def testCodexAuth: Result = {
    val parsed = CodexAuthFile.parse(codexAuth)
    Result.all(
      List(
        parsed.map(_.accessToken.value.value) ==== Right("eyJ-access"),
        parsed.map(_.accountId.map(_.value.value)) ==== Right(Some("acct-123")),
      )
    )
  }

  def testCodexAuthApiKey: Result =
    CodexAuthFile.parse("""{"auth_mode":"apikey","OPENAI_API_KEY":"sk-proj-x","tokens":null}""") ====
      Left("Codex is in API-key mode, not supported in v1.")
}
