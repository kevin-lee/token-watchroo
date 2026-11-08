package tokenwatchroo.core

import cats.syntax.all.*
import hedgehog.*
import hedgehog.runner.*
import java.io.StringReader
import java.util.Properties as JProperties
import refined4s.types.all.*
import scala.jdk.CollectionConverters.*
import tokenwatchroo.core.providers.CredentialProblem

/** The message bundles (#10): every key in both languages, the placeholders, the copy in both languages, and the
  * Korean width budgets measured on 2026-11-09 against the Alerts artboard and the 2-line card text.
  */
object MessagesSpec extends Properties {

  override def tests: List[Test] = List(
    example("every MessageKey is in both bundles", testEveryKeyPresent),
    example("no bundle has a key that is not a MessageKey", testNoExtraKeys),
    example("every template has the placeholders of its key in both bundles", testPlaceholders),
    example("parse rejects a missing key, an extra key and a wrong placeholder", testParseRejects),
    property("every alert renders in both languages with no placeholder left", testAlertsRender),
    example("Korean notification copy for each kind", testKoreanAlerts),
    example("English notification copy keeps the design copy", testEnglishAlerts),
    example("durations in both languages", testDurations),
    example("window words in both languages", testWindowWords),
    example("Korean text stays within the measured width budgets", testKoreanWidths),
    example("a value that holds a placeholder is not replaced again", testOnePass),
  )

  private val languages: List[AppLanguage] = AppLanguage.all

  private def entries(language: AppLanguage): Map[String, String] = {
    val properties = new JProperties()
    properties.load(new StringReader(TestMessages.text(language)))
    properties.asScala.toMap
  }

  private def messages(language: AppLanguage): Messages = TestMessages.bundles.of(language)

  def testEveryKeyPresent: Result =
    Result.all(
      for {
        language <- languages
        key      <- MessageKey.all
      } yield Result.assert(entries(language).contains(key.key)).log(s"${language.wire}: ${key.key}")
    )

  def testNoExtraKeys: Result =
    Result.all(
      languages.map { language =>
        entries(language).keySet.filter(MessageKey.fromKey(_).isEmpty) ==== Set.empty[String]
      }
    )

  def testPlaceholders: Result =
    Result.all(
      for {
        language <- languages
        key      <- MessageKey.all
      } yield (Messages.placeholders(entries(language).getOrElse(key.key, "")) ==== key.params.toSet)
        .log(s"${language.wire}: ${key.key}")
    )

  def testParseRejects: Result = {
    val text    = TestMessages.text(AppLanguage.En)
    val missing = text.linesIterator.filterNot(_.startsWith("error.timeout")).mkString("\n")
    val extra   = s"$text\nerror.extra = Extra\n"
    val wrong   = text.replace("error.http = Usage API error HTTP {status}", "error.http = Usage API error HTTP {code}")
    Result.all(
      List(
        Messages.parse(AppLanguage.En, missing) ==== "Bundle en: missing keys: error.timeout".asLeft[Messages],
        Messages.parse(AppLanguage.En, extra) ==== "Bundle en: unknown keys: error.extra".asLeft[Messages],
        Messages.parse(AppLanguage.En, wrong) ==== "Bundle en: wrong placeholders: error.http".asLeft[Messages],
      )
    )
  }

  private val now: EpochSeconds = EpochSeconds(1789185600L)

  def testAlertsRender: Property =
    for {
      agent    <- Fixtures.genAgentId.log("agent")
      window   <- Fixtures.genUsageWindow.log("window")
      spend    <- Fixtures.genSpend.log("spend")
      language <- Gen.elementUnsafe(languages).log("language")
    } yield {
      val m      = messages(language)
      val level  = AlertLevel.unsafeFrom(70)
      val alerts = List(
        AlertText.level(agent, window, now, level, m),
        AlertText.reset(agent, window, now, m),
        AlertText.spendLevel(agent, spend, now, SpendAlertKind.percent(level), m),
        AlertText.spendReset(agent, spend, m),
      )
      Result.all(
        alerts.flatMap { alert =>
          List(
            Result.assert(!s"${alert.title}${alert.body}".exists(c => c === '{' || c === '}')).log(alert.toString),
            Result.assert(alert.title.contains(agent.displayName)).log(alert.title),
          )
        }
      )
    }

  private def model(name: String): WindowId = WindowId.model(ModelName(NonEmptyString.unsafeFrom(name)))

  private def titleAndBody(alert: Alert): (String, String) = (alert.title, alert.body)

  def testKoreanAlerts: Result = {
    val ko     = TestMessages.ko
    val level  = AlertLevel.unsafeFrom(70)
    val in11m  = EpochSeconds(now.value + 660L).some
    val in30s  = EpochSeconds(now.value + 30L).some
    val in19d  = EpochSeconds(now.value + 19L * 86400L).some
    val budget = (spent: String) => Fixtures.spend(Fixtures.usd, spent, "200", in19d)
    val credit = (spent: String) => Fixtures.spend(Currency.credits, spent, "10000", none[EpochSeconds])
    Result.all(
      List(
        titleAndBody(
          AlertText.level(AgentId.ClaudeCode, Fixtures.window(WindowId.Session, 96.0d, in11m), now, level, ko)
        ) ==== (("Claude Code 사용량 거의 소진", "5시간 한도의 96% 사용. 11분 후 초기화됩니다.")),
        titleAndBody(
          AlertText.level(AgentId.Codex, Fixtures.window(model("Sonnet 4.5"), 72.0d, in30s), now, level, ko)
        ) ==== (("Codex 주간 Sonnet 4.5 한도 임박", "주간 Sonnet 4.5 한도의 72% 사용. 곧 초기화됩니다.")),
        titleAndBody(
          AlertText.reset(AgentId.ClaudeCode, Fixtures.window(WindowId.Weekly, 1.0d, in19d), now, ko)
        ) ==== (("Claude Code 주간 한도 초기화", "새 주간 한도를 사용할 수 있습니다.")),
        titleAndBody(
          AlertText.spendLevel(AgentId.ClaudeCode, budget("180"), now, SpendAlertKind.percent(level), ko)
        ) ==== (("Claude Code 월 예산 거의 소진", "$200.00 중 $180.00 사용, 90%. 19일 후 초기화됩니다.")),
        titleAndBody(
          AlertText.spendLevel(AgentId.ClaudeCode, budget("145"), now, SpendAlertKind.percent(level), ko)
        ) ==== (("Claude Code 월 예산 한도 임박", "$200.00 중 $145.00 사용, 73%. 19일 후 초기화됩니다.")),
        titleAndBody(
          AlertText.spendLevel(AgentId.Codex, credit("8600"), now, SpendAlertKind.percent(level), ko)
        ) ==== (("Codex 크레딧 거의 소진", "10,000 크레딧 중 8,600 사용, 86%.")),
        titleAndBody(
          AlertText.spendLevel(AgentId.Codex, credit("7300"), now, SpendAlertKind.percent(level), ko)
        ) ==== (("Codex 크레딧 한도 임박", "10,000 크레딧 중 7,300 사용, 73%.")),
        titleAndBody(AlertText.spendReset(AgentId.ClaudeCode, budget("0"), ko)) ====
          (("Claude Code 예산 초기화", "새 월 예산을 사용할 수 있습니다.")),
        titleAndBody(AlertText.spendReset(AgentId.Codex, credit("0"), ko)) ====
          (("Codex 크레딧 초기화", "크레딧 한도를 다시 사용할 수 있습니다.")),
      )
    )
  }

  def testEnglishAlerts: Result = {
    val en    = TestMessages.en
    val level = AlertLevel.unsafeFrom(70)
    val in30s = EpochSeconds(now.value + 30L).some
    Result.all(
      List(
        titleAndBody(
          AlertText.level(
            AgentId.ClaudeCode,
            Fixtures.window(WindowId.Session, 96.0d, EpochSeconds(now.value + 660L).some),
            now,
            level,
            en,
          )
        ) ==== (("Claude Code is almost out", "96% of the 5 h window used. Resets in 11 minutes.")),
        titleAndBody(AlertText.level(AgentId.Codex, Fixtures.window(model("Fable"), 72.0d, in30s), now, level, en)) ====
          (("Codex is near its weekly Fable limit", "72% of the weekly Fable window used. Resets in under a minute.")),
      )
    )
  }

  def testDurations: Result =
    Result.all(
      List(
        TestMessages.ko.duration(Seconds(30L)) ==== "1분 미만",
        TestMessages.ko.duration(Seconds(660L)) ==== "11분",
        TestMessages.ko.duration(Seconds(4320L)) ==== "1시간 12분",
        TestMessages.ko.duration(Seconds(183600L)) ==== "2일 3시간",
        TestMessages.ko.duration(Seconds(86400L)) ==== "1일",
        TestMessages.en.duration(Seconds(4320L)) ==== "1 hour 12 minutes",
      )
    )

  def testWindowWords: Result =
    Result.all(
      List(
        TestMessages.en.windowDescribe(WindowId.Session) ==== "5 h window",
        TestMessages.en.windowDescribe(WindowId.Weekly) ==== "weekly window",
        TestMessages.en.windowDescribe(model("Fable")) ==== "weekly Fable window",
        TestMessages.en.windowLimit(WindowId.Session) ==== "session",
        TestMessages.en.windowLimit(WindowId.Weekly) ==== "weekly",
        TestMessages.en.windowLimit(model("Fable")) ==== "weekly Fable",
        TestMessages.ko.windowDescribe(WindowId.Session) ==== "5시간 한도",
        TestMessages.ko.windowDescribe(model("Fable")) ==== "주간 Fable 한도",
        TestMessages.ko.windowLimit(WindowId.Weekly) ==== "주간",
        TestMessages.ko.windowLimit(model("Fable")) ==== "주간 Fable",
      )
    )

  /** A Hangul syllable is about twice as wide as other characters in the system font: 37 columns measured 231 pt for
    * a title, 64 columns 381 pt for a body and 119 columns 568 pt for a card text (2026-11-09).
    */
  private def columns(text: String): Int = text.map(c => if (c >= '가' && c <= '힣') 2 else 1).sum

  private val TitleBudget: Int = 44
  private val BodyBudget: Int  = 96
  private val CardBudget: Int  = 120

  def testKoreanWidths: Result = {
    val ko     = TestMessages.ko
    val sonnet = model("Sonnet 4.5")
    val in7d   = EpochSeconds(now.value + 6L * 86400L + 23L * 3600L).some
    val big    = Fixtures.spend(Fixtures.usd, "17000.50", "20000", EpochSeconds(now.value + 19L * 86400L + 82800L).some)
    val level  = AlertLevel.unsafeFrom(70)
    val windowLow  = AlertText.level(AgentId.ClaudeCode, Fixtures.window(sonnet, 72.0d, in7d), now, level, ko)
    val windowHigh = AlertText.level(AgentId.ClaudeCode, Fixtures.window(sonnet, 96.0d, in7d), now, level, ko)
    val spendHigh  = AlertText.spendLevel(AgentId.ClaudeCode, big, now, SpendAlertKind.percent(level), ko)
    val resets     = AlertText.reset(AgentId.ClaudeCode, Fixtures.window(sonnet, 1.0d, in7d), now, ko)
    val alerts     = List(windowLow, windowHigh, spendHigh, resets)
    val cards      = List(
      CredentialProblem.ClaudeNoSignIn,
      CredentialProblem.ClaudeSignInExpired,
      CredentialProblem.ClaudeNoAccessToken,
      CredentialProblem.ClaudeTokenExpired,
      CredentialProblem.CodexApiKeyMode,
      CredentialProblem.CodexNoAccessToken,
      CredentialProblem.CodexHomeMissing,
    ).map(ko.credentialProblem) ++ List(
      ko.credentialsNoProfileScope,
      ko.credentialsConfigDirNoSignIn,
      ko.errorNotSignedIn("claude"),
      ko.errorAccessDenied,
      ko.errorTokenExpired(401, "claude"),
      ko.errorRateLimited,
      ko.errorTimeout,
      ko.errorNoUsageLimit,
      ko.errorApiNotSupported,
      ko.codexCompressedLog(ko.errorHttp(500)),
    )
    Result.all(
      alerts.flatMap { alert =>
        List(
          Result.assert(columns(alert.title) <= TitleBudget).log(s"${columns(alert.title)}: ${alert.title}"),
          Result.assert(columns(alert.body) <= BodyBudget).log(s"${columns(alert.body)}: ${alert.body}"),
        )
      } ++ cards.map(card => Result.assert(columns(card) <= CardBudget).log(s"${columns(card)}: $card"))
    )
  }

  def testOnePass: Result =
    TestMessages.en.errorNetwork("bad {detail} {cli}") ==== "Network error: bad {detail} {cli}"
}
