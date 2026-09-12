package tokenwatchroo.core

import cats.syntax.all.*
import hedgehog.*
import hedgehog.runner.*
import refined4s.types.all.*
import tokenwatchroo.core.codecs.given
import tokenwatchroo.core.providers.*

object ResponseDecodingSpec extends Properties {

  override def tests: List[Test] = List(
    example(
      "Claude usage response without limits becomes session, weekly, and the fixed per-model windows that have a reset",
      testClaudeUsage,
    ),
    example(
      "Claude usage response with missing limits gives idle session and weekly windows and no per-model rows",
      testClaudeUsageIdle,
    ),
    example(
      "Claude usage response with a limits array gives a Fable row from the weekly_scoped entry",
      testLimitsFable
    ),
    example(
      "Claude usage response with limits present and no weekly_scoped entry gives no per-model rows",
      testLimitsNoScoped,
    ),
    example("Claude usage response with limits null falls back to the fixed keys", testLimitsNull),
    example(
      "weekly_scoped entries without a reset, without a name, or with an unknown kind are omitted, a repeated name keeps the first entry, and rows sort by name",
      testTwoScoped,
    ),
    example("a malformed resets_at in a weekly_scoped entry fails the mapping", testMalformedScopedReset),
    property("per-model rows come out sorted by name", testSortedByName),
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

  private def model(name: String): WindowId = WindowId.model(ModelName(NonEmptyString.unsafeFrom(name)))

  private def claudeWindows(json: String): Either[String, List[UsageWindow]] =
    codecs.readEither[ClaudeUsageResponse](json).leftMap(_.message).flatMap(_.toWindows.leftMap(_.message))

  def testClaudeUsage: Result = {
    val windows = claudeWindows(claudeUsage)
    Result.all(
      List(
        windows.map(_.map(_.id)) ==== Right(List(WindowId.Session, WindowId.Weekly, model("Sonnet"))),
        windows.map(_.map(_.usedPercent)) ==== Right(
          List(UsedPercent.clamp(42.5d), UsedPercent.clamp(17.0d), UsedPercent.clamp(25.0d))
        ),
        windows.map(_.flatMap(_.resetsAt)) ==== Right(
          List(
            Iso8601.parseToEpochSeconds("2026-05-11T18:00:00Z"),
            Iso8601.parseToEpochSeconds("2026-05-18T00:00:00Z"),
            Iso8601.parseToEpochSeconds("2026-05-18T00:00:00Z"),
          ).flatMap(_.toOption)
        ),
        windows.map(_.flatMap(_.windowLength)) ==== Right(List(Seconds(18000L), Seconds(604800L), Seconds(604800L))),
      )
    )
  }

  /** The verified shape of 2026-09-12, trimmed to the relevant keys. */
  private val claudeUsageWithLimits =
    """{"five_hour":{"utilization":24.0,"resets_at":"2026-09-12T15:20:00.361743+00:00"},"seven_day":{"utilization":45.0,"resets_at":"2026-09-12T18:00:00.361764+00:00"},"seven_day_opus":null,"seven_day_sonnet":null,"seven_day_oauth_apps":null,"extra_usage":{"is_enabled":false},"limits":[{"kind":"session","group":"session","percent":24,"severity":"normal","resets_at":"2026-09-12T15:20:00.361743+00:00","scope":null,"is_active":false},{"kind":"weekly_all","group":"weekly","percent":45,"severity":"normal","resets_at":"2026-09-12T18:00:00.361764+00:00","scope":null,"is_active":false},{"kind":"weekly_scoped","group":"weekly","percent":68,"severity":"normal","resets_at":"2026-09-12T18:00:00.361932+00:00","scope":{"model":{"id":null,"display_name":"Fable"},"surface":null},"is_active":true}],"spend":{"percent":0},"member_dashboard_available":false}"""

  private val claudeUsageTwoScoped =
    """{"five_hour":{"utilization":24.0,"resets_at":"2026-09-12T15:20:00.361743+00:00"},"seven_day":{"utilization":45.0,"resets_at":"2026-09-12T18:00:00.361764+00:00"},"limits":[{"kind":"weekly_scoped","group":"weekly","percent":30,"severity":"normal","resets_at":"2026-09-12T18:00:00Z","scope":{"model":{"id":null,"display_name":"Opus"},"surface":null},"is_active":false},{"kind":"weekly_scoped","group":"weekly","percent":50,"severity":"normal","resets_at":null,"scope":{"model":{"id":null,"display_name":"Sonnet"},"surface":null},"is_active":false},{"kind":"weekly_scoped","group":"weekly","percent":10,"severity":"normal","resets_at":"2026-09-12T18:00:00Z","scope":null,"is_active":false},{"kind":"monthly_scoped","group":"monthly","percent":5,"severity":"normal","resets_at":"2026-09-12T18:00:00Z","scope":{"model":{"id":null,"display_name":"Haiku"},"surface":null},"is_active":false},{"kind":"weekly_scoped","group":"weekly","percent":68,"severity":"normal","resets_at":"2026-09-12T18:00:00Z","scope":{"model":{"id":null,"display_name":"Fable"},"surface":null},"is_active":true},{"kind":"weekly_scoped","group":"weekly","percent":70,"severity":"normal","resets_at":"2026-09-12T18:00:00Z","scope":{"model":{"id":null,"display_name":"Fable"},"surface":null},"is_active":false}]}"""

  private val claudeUsageLimitsNoScoped =
    """{"five_hour":{"utilization":24.0,"resets_at":"2026-09-12T15:20:00Z"},"seven_day":{"utilization":45.0,"resets_at":"2026-09-12T18:00:00Z"},"seven_day_opus":{"utilization":10.0,"resets_at":"2026-09-12T18:00:00Z"},"limits":[{"kind":"session","group":"session","percent":24,"severity":"normal","resets_at":"2026-09-12T15:20:00Z","scope":null,"is_active":false},{"kind":"weekly_all","group":"weekly","percent":45,"severity":"normal","resets_at":"2026-09-12T18:00:00Z","scope":null,"is_active":false}]}"""

  private val claudeUsageLimitsNull =
    """{"five_hour":{"utilization":24.0,"resets_at":"2026-09-12T15:20:00Z"},"seven_day":{"utilization":45.0,"resets_at":"2026-09-12T18:00:00Z"},"seven_day_opus":{"utilization":10.0,"resets_at":"2026-09-12T18:00:00Z"},"seven_day_sonnet":null,"limits":null}"""

  def testLimitsFable: Result = {
    val windows = claudeWindows(claudeUsageWithLimits)
    Result.all(
      List(
        windows.map(_.map(_.id)) ==== Right(List(WindowId.Session, WindowId.Weekly, model("Fable"))),
        windows.map(_.map(_.usedPercent)) ==== Right(
          List(UsedPercent.clamp(24.0d), UsedPercent.clamp(45.0d), UsedPercent.clamp(68.0d))
        ),
        windows.map(_.lastOption.flatMap(_.resetsAt)) ==== Right(
          Iso8601.parseToEpochSeconds("2026-09-12T18:00:00.361932+00:00").toOption
        ),
        windows.map(_.lastOption.flatMap(_.windowLength)) ==== Right(Some(Seconds(604800L))),
        windows.map(_.map(_.isIdle)) ==== Right(List(false, false, false)),
      )
    )
  }

  def testLimitsNoScoped: Result =
    claudeWindows(claudeUsageLimitsNoScoped).map(_.map(_.id)) ==== Right(List(WindowId.Session, WindowId.Weekly))

  def testLimitsNull: Result = {
    val windows = claudeWindows(claudeUsageLimitsNull)
    Result.all(
      List(
        windows.map(_.map(_.id)) ==== Right(List(WindowId.Session, WindowId.Weekly, model("Opus"))),
        windows.map(_.lastOption.map(_.usedPercent)) ==== Right(Some(UsedPercent.clamp(10.0d))),
      )
    )
  }

  def testTwoScoped: Result = {
    val windows = claudeWindows(claudeUsageTwoScoped)
    Result.all(
      List(
        windows.map(_.map(_.id)) ==== Right(List(WindowId.Session, WindowId.Weekly, model("Fable"), model("Opus"))),
        windows.map(_.map(_.usedPercent)) ==== Right(
          List(UsedPercent.clamp(24.0d), UsedPercent.clamp(45.0d), UsedPercent.clamp(68.0d), UsedPercent.clamp(30.0d))
        ),
      )
    )
  }

  def testMalformedScopedReset: Result =
    claudeWindows(
      """{"limits":[{"kind":"weekly_scoped","percent":68,"resets_at":"soon","scope":{"model":{"display_name":"Fable"}}}]}"""
    ).isLeft ==== true

  def testSortedByName: Property =
    for {
      names <- Fixtures.genPlainModelName.list(Range.linear(0, 6)).map(_.distinct).log("names")
    } yield {
      val entries  = names.map { name =>
        ClaudeLimitEntry(
          "weekly_scoped".some,
          none[String],
          10.0d.some,
          none[String],
          "2026-09-12T18:00:00Z".some,
          ClaudeLimitScope(ClaudeLimitModel(none[String], name.value.value.some).some, none[String]).some,
          none[Boolean],
        )
      }
      val response =
        ClaudeUsageResponse(none[ClaudeLimit], none[ClaudeLimit], none[ClaudeLimit], none[ClaudeLimit], entries.some)
      response.toWindows.map(_.drop(2).map(_.id)) ==== Right(names.sortBy(_.value.value).map(WindowId.model))
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
