package tokenwatchroo.core

import cats.{Eq, Show}
import cats.syntax.all.*
import extras.render.syntax.*
import java.io.StringReader
import java.util.Properties
import scala.jdk.CollectionConverters.*
import scala.util.matching.Regex
import tokenwatchroo.core.providers.CredentialProblem

/** The user-visible text of the core in one language (#10), with the number rules of the macOS region. Pure functions
  * that make text take it as a parameter, so the language is never global state and tests can render both languages.
  * One method for each message: the placeholders are filled with the `Render` text of typed values.
  */
final case class Messages private (
  language: AppLanguage,
  numberFormat: NumberFormat,
  templates: Map[MessageKey, String],
) derives CanEqual

object Messages {

  given eq: Eq[Messages]     = Eq.fromUniversalEquals
  given show: Show[Messages] = Show.show(m => s"Messages(${m.language.wire})")

  /** Reads a bundle (`key = value` lines, UTF-8 text already decoded) with `java.util.Properties`. Fails with every
    * missing key, every unknown key and every key whose placeholders differ from `MessageKey.params`.
    */
  def parse(language: AppLanguage, text: String): Either[String, Messages] = {
    val properties = new Properties()
    properties.load(new StringReader(text))
    val entries    = properties.asScala.toMap
    val missing    = MessageKey.all.filterNot(key => entries.contains(key.key)).map(_.key)
    val unknown    = entries.keys.toList.filter(MessageKey.fromKey(_).isEmpty).sorted
    val templates  = MessageKey.all.flatMap(key => entries.get(key.key).map(key -> _)).toMap
    val wrong      = MessageKey
      .all
      .filter(key => templates.get(key).exists(template => placeholders(template) =!= key.params.toSet))
      .map(_.key)
    val problems   = List(
      Option.when(missing.nonEmpty)(s"missing keys: ${missing.mkString(", ")}"),
      Option.when(unknown.nonEmpty)(s"unknown keys: ${unknown.mkString(", ")}"),
      Option.when(wrong.nonEmpty)(s"wrong placeholders: ${wrong.mkString(", ")}"),
    ).flatten
    if (problems.isEmpty) Messages(language, NumberFormat.default, templates).asRight[String]
    else s"Bundle ${language.wire}: ${problems.mkString("; ")}".asLeft[Messages]
  }

  /** The `{name}` placeholders of a template. */
  def placeholders(template: String): Set[String] =
    PlaceholderPattern.findAllMatchIn(template).map(_.group(1)).toSet

  extension (messages: Messages) {

    def withNumberFormat(numberFormat: NumberFormat): Messages = messages.copy(numberFormat = numberFormat)

    /* Window words */

    /** "5 h window", "weekly window", "weekly <name> window". */
    def windowDescribe(window: WindowId): String = window match {
      case WindowId.Session => fill(MessageKey.WindowDescribeSession)
      case WindowId.Weekly => fill(MessageKey.WindowDescribeWeekly)
      case WindowId.Model(name) => fill(MessageKey.WindowDescribeModel, "model" -> name.render)
    }

    /** The noun in notification titles: "session", "weekly", "weekly <name>". */
    def windowLimit(window: WindowId): String = window match {
      case WindowId.Session => fill(MessageKey.WindowLimitSession)
      case WindowId.Weekly => fill(MessageKey.WindowLimitWeekly)
      case WindowId.Model(name) => fill(MessageKey.WindowLimitModel, "model" -> name.render)
    }

    /* Notifications */

    def alertTitleAlmostOut(agent: AgentId): String = fill(MessageKey.AlertTitleAlmostOut, "agent" -> agent.render)

    def alertTitleNear(agent: AgentId, window: WindowId): String =
      fill(MessageKey.AlertTitleNear, "agent" -> agent.render, "limit" -> windowLimit(window))

    def alertTitleReset(agent: AgentId, window: WindowId): String =
      fill(MessageKey.AlertTitleReset, "agent" -> agent.render, "limit" -> windowLimit(window))

    def alertBodyUsed(percent: UsedPercent, window: WindowId): String =
      fill(MessageKey.AlertBodyUsed, "percent" -> percent.render, "window" -> windowDescribe(window))

    def alertBodyFresh(window: WindowId): String = fill(MessageKey.AlertBodyFresh, "window" -> windowDescribe(window))

    def alertResetsIn(remaining: Seconds): String = fill(MessageKey.AlertResetsIn, "duration" -> duration(remaining))

    def alertResetsSoon: String = fill(MessageKey.AlertResetsSoon)

    def alertTitleSpendAlmostOut(agent: AgentId, currency: Currency): String = currency match {
      case Currency.Iso(_) => fill(MessageKey.AlertTitleSpendAlmostOutBudget, "agent" -> agent.render)
      case Currency.Credits => fill(MessageKey.AlertTitleSpendAlmostOutCredits, "agent" -> agent.render)
    }

    def alertTitleSpendNear(agent: AgentId, currency: Currency): String = currency match {
      case Currency.Iso(_) => fill(MessageKey.AlertTitleSpendNearBudget, "agent" -> agent.render)
      case Currency.Credits => fill(MessageKey.AlertTitleSpendNearCredits, "agent" -> agent.render)
    }

    def alertBodySpend(spend: Spend): String =
      fill(MessageKey.AlertBodySpend, "summary" -> spendSummary(spend), "percent" -> spend.usedPercent.render)

    def alertTitleSpendReset(agent: AgentId, currency: Currency): String = currency match {
      case Currency.Iso(_) => fill(MessageKey.AlertTitleSpendResetBudget, "agent" -> agent.render)
      case Currency.Credits => fill(MessageKey.AlertTitleSpendResetCredits, "agent" -> agent.render)
    }

    def alertBodySpendReset(currency: Currency): String = currency match {
      case Currency.Iso(_) => fill(MessageKey.AlertBodySpendResetBudget)
      case Currency.Credits => fill(MessageKey.AlertBodySpendResetCredits)
    }

    /* Amounts and durations */

    /** `Format.money` with the number rules of the region. */
    def money(amount: Amount, currency: Currency): String = Format.money(amount, currency, messages.numberFormat)

    /** "$246.00 of $300.00 spent", or "8,000 of 10,000 credits spent". */
    def spendSummary(spend: Spend): String = {
      val spent = money(spend.spent, spend.currency)
      val limit = money(spend.limit, spend.currency)
      spend.currency match {
        case Currency.Credits => fill(MessageKey.SpendSummaryCredits, "spent" -> spent, "limit" -> limit)
        case Currency.Iso(_) => fill(MessageKey.SpendSummaryCurrency, "spent" -> spent, "limit" -> limit)
      }
    }

    /** "under a minute", "11 minutes", "1 hour 12 minutes", "2 days 3 hours". A zero minor unit is left out. */
    def duration(seconds: Seconds): String = {
      val total   = math.max(0L, seconds.value)
      val days    = total / 86400L
      val hours   = (total % 86400L) / 3600L
      val minutes = (total % 3600L) / 60L
      if (total < 60L) fill(MessageKey.DurationUnderMinute)
      else if (days > 0L) joinUnits(days, MessageKey.DurationDayOne, MessageKey.DurationDayOther, hours, hourUnit)
      else if (hours > 0L)
        joinUnits(hours, MessageKey.DurationHourOne, MessageKey.DurationHourOther, minutes, minuteUnit)
      else minuteUnit(minutes)
    }

    /* Card texts */

    def errorNotSignedIn(cli: String): String = fill(MessageKey.ErrorNotSignedIn, "cli" -> cli)

    def errorAccessDenied: String = fill(MessageKey.ErrorAccessDenied)

    def errorTokenExpired(status: Int, cli: String): String =
      fill(MessageKey.ErrorTokenExpired, "status" -> status.toString, "cli" -> cli)

    def errorRateLimited: String = fill(MessageKey.ErrorRateLimited)

    def errorHttp(status: Int): String = fill(MessageKey.ErrorHttp, "status" -> status.toString)

    def errorNetwork(detail: String): String = fill(MessageKey.ErrorNetwork, "detail" -> detail)

    def errorDecode(detail: String): String = fill(MessageKey.ErrorDecode, "detail" -> detail)

    def errorTimeout: String = fill(MessageKey.ErrorTimeout)

    def errorNoUsageLimit: String = fill(MessageKey.ErrorNoUsageLimit)

    def errorApiNotSupported: String = fill(MessageKey.ErrorApiNotSupported)

    def errorUnknown: String = fill(MessageKey.ErrorUnknown)

    def credentialsNoProfileScope: String = fill(MessageKey.CredentialsClaudeNoProfileScope)

    def credentialsConfigDirNoSignIn: String = fill(MessageKey.CredentialsClaudeConfigDirNoSignIn)

    def credentialProblem(problem: CredentialProblem): String = problem match {
      case CredentialProblem.ClaudeNoSignIn => fill(MessageKey.CredentialsClaudeNoSignIn)
      case CredentialProblem.ClaudeSignInExpired => fill(MessageKey.CredentialsClaudeSignInExpired)
      case CredentialProblem.ClaudeNoAccessToken => fill(MessageKey.CredentialsClaudeNoAccessToken)
      case CredentialProblem.ClaudeTokenExpired => fill(MessageKey.CredentialsClaudeTokenExpired)
      case CredentialProblem.CodexApiKeyMode => fill(MessageKey.CredentialsCodexApiKeyMode)
      case CredentialProblem.CodexNoAccessToken => fill(MessageKey.CredentialsCodexNoAccessToken)
      case CredentialProblem.Unreadable(detail) => fill(MessageKey.CredentialsUnreadable, "detail" -> detail)
      case CredentialProblem.ConfigDirUnreadable(reason) =>
        fill(MessageKey.CredentialsClaudeConfigDirUnreadable, "reason" -> reason)
      case CredentialProblem.CodexHomeMissing => fill(MessageKey.CredentialsCodexHomeMissing)
      case CredentialProblem.FileUnreadable(file, detail) =>
        fill(MessageKey.CredentialsFileUnreadable, "file" -> file, "detail" -> detail)
      case CredentialProblem.SecurityFailed(code, output) =>
        fill(MessageKey.CredentialsSecurityFailed, "code" -> code.toString, "output" -> output)
      case CredentialProblem.ProcessFailed(command, detail) =>
        fill(MessageKey.CredentialsProcessFailed, "command" -> command, "detail" -> detail)
    }

    /** The API error, then the note that the newest Codex log is compressed (#81). */
    def codexCompressedLog(error: String): String = fill(MessageKey.CodexCompressedLog, "error" -> error)

    private def minuteUnit(n: Long): String = unit(n, MessageKey.DurationMinuteOne, MessageKey.DurationMinuteOther)

    private def hourUnit(n: Long): String = unit(n, MessageKey.DurationHourOne, MessageKey.DurationHourOther)

    private def unit(n: Long, one: MessageKey, other: MessageKey): String =
      if (n === 1L) fill(one) else fill(other, "n" -> n.toString)

    private def joinUnits(
      major: Long,
      majorOne: MessageKey,
      majorOther: MessageKey,
      minor: Long,
      minorUnit: Long => String,
    ): String = {
      val majorText = unit(major, majorOne, majorOther)
      if (minor === 0L) majorText else s"$majorText ${minorUnit(minor)}"
    }

    /** The template with each `{name}` replaced in one pass, so a value that holds `{x}` (an exception text) is never
      * replaced again. `parse` guarantees that every key has a template.
      */
    private def fill(key: MessageKey, args: (String, String)*): String = {
      val values = args.toMap
      PlaceholderPattern.replaceAllIn(
        messages.templates.getOrElse(key, key.key),
        m => Regex.quoteReplacement(values.getOrElse(m.group(1), m.matched)),
      )
    }
  }

  private val PlaceholderPattern: Regex = "\\{([A-Za-z]+)\\}".r
}
