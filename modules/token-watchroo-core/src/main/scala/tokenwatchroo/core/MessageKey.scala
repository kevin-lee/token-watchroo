package tokenwatchroo.core

import cats.{Eq, Show}

/** Every key of the message bundles (#10), with the names of its `{name}` placeholders. `Messages.parse` rejects a
  * bundle that lacks a key, has another key, or has other placeholders, so a used key always has a text in every
  * language.
  */
enum MessageKey(val key: String, val params: List[String]) derives CanEqual {
  case WindowDescribeSession extends MessageKey("window.describe.session", Nil)
  case WindowDescribeWeekly extends MessageKey("window.describe.weekly", Nil)
  case WindowDescribeModel extends MessageKey("window.describe.model", List("model"))
  case WindowLimitSession extends MessageKey("window.limit.session", Nil)
  case WindowLimitWeekly extends MessageKey("window.limit.weekly", Nil)
  case WindowLimitModel extends MessageKey("window.limit.model", List("model"))
  case AlertTitleAlmostOut extends MessageKey("alert.title.almostOut", List("agent"))
  case AlertTitleNear extends MessageKey("alert.title.near", List("agent", "limit"))
  case AlertTitleReset extends MessageKey("alert.title.reset", List("agent", "limit"))
  case AlertBodyUsed extends MessageKey("alert.body.used", List("percent", "window"))
  case AlertBodyFresh extends MessageKey("alert.body.fresh", List("window"))
  case AlertResetsIn extends MessageKey("alert.resetsIn", List("duration"))
  case AlertResetsSoon extends MessageKey("alert.resetsSoon", Nil)
  case AlertTitleSpendAlmostOutBudget extends MessageKey("alert.title.spend.almostOut.budget", List("agent"))
  case AlertTitleSpendAlmostOutCredits extends MessageKey("alert.title.spend.almostOut.credits", List("agent"))
  case AlertTitleSpendNearBudget extends MessageKey("alert.title.spend.near.budget", List("agent"))
  case AlertTitleSpendNearCredits extends MessageKey("alert.title.spend.near.credits", List("agent"))
  case AlertBodySpend extends MessageKey("alert.body.spend", List("summary", "percent"))
  case AlertTitleSpendResetBudget extends MessageKey("alert.title.spendReset.budget", List("agent"))
  case AlertTitleSpendResetCredits extends MessageKey("alert.title.spendReset.credits", List("agent"))
  case AlertBodySpendResetBudget extends MessageKey("alert.body.spendReset.budget", Nil)
  case AlertBodySpendResetCredits extends MessageKey("alert.body.spendReset.credits", Nil)
  case SpendSummaryCurrency extends MessageKey("spend.summary.currency", List("spent", "limit"))
  case SpendSummaryCredits extends MessageKey("spend.summary.credits", List("spent", "limit"))
  case DurationUnderMinute extends MessageKey("duration.underMinute", Nil)
  case DurationMinuteOne extends MessageKey("duration.minute.one", Nil)
  case DurationMinuteOther extends MessageKey("duration.minute.other", List("n"))
  case DurationHourOne extends MessageKey("duration.hour.one", Nil)
  case DurationHourOther extends MessageKey("duration.hour.other", List("n"))
  case DurationDayOne extends MessageKey("duration.day.one", Nil)
  case DurationDayOther extends MessageKey("duration.day.other", List("n"))
  case ErrorNotSignedIn extends MessageKey("error.notSignedIn", List("cli"))
  case ErrorAccessDenied extends MessageKey("error.accessDenied", Nil)
  case ErrorTokenExpired extends MessageKey("error.tokenExpired", List("status", "cli"))
  case ErrorRateLimited extends MessageKey("error.rateLimited", Nil)
  case ErrorHttp extends MessageKey("error.http", List("status"))
  case ErrorNetwork extends MessageKey("error.network", List("detail"))
  case ErrorDecode extends MessageKey("error.decode", List("detail"))
  case ErrorTimeout extends MessageKey("error.timeout", Nil)
  case ErrorNoUsageLimit extends MessageKey("error.noUsageLimit", Nil)
  case ErrorApiNotSupported extends MessageKey("error.apiNotSupported", Nil)
  case ErrorUnknown extends MessageKey("error.unknown", Nil)
  case CredentialsUnreadable extends MessageKey("credentials.unreadable", List("detail"))
  case CredentialsClaudeNoSignIn extends MessageKey("credentials.claude.noSignIn", Nil)
  case CredentialsClaudeSignInExpired extends MessageKey("credentials.claude.signInExpired", Nil)
  case CredentialsClaudeNoAccessToken extends MessageKey("credentials.claude.noAccessToken", Nil)
  case CredentialsClaudeTokenExpired extends MessageKey("credentials.claude.tokenExpired", Nil)
  case CredentialsClaudeNoProfileScope extends MessageKey("credentials.claude.noProfileScope", Nil)
  case CredentialsClaudeConfigDirNoSignIn extends MessageKey("credentials.claude.configDirNoSignIn", Nil)
  case CredentialsClaudeConfigDirUnreadable extends MessageKey("credentials.claude.configDirUnreadable", List("reason"))
  case CredentialsCodexApiKeyMode extends MessageKey("credentials.codex.apiKeyMode", Nil)
  case CredentialsCodexNoAccessToken extends MessageKey("credentials.codex.noAccessToken", Nil)
  case CredentialsCodexHomeMissing extends MessageKey("credentials.codex.homeMissing", Nil)
  case CredentialsFileUnreadable extends MessageKey("credentials.fileUnreadable", List("file", "detail"))
  case CredentialsSecurityFailed extends MessageKey("credentials.securityFailed", List("code", "output"))
  case CredentialsProcessFailed extends MessageKey("credentials.processFailed", List("command", "detail"))
  case CodexCompressedLog extends MessageKey("codex.compressedLog", List("error"))
}

object MessageKey {
  val all: List[MessageKey] = MessageKey.values.toList

  given eq: Eq[MessageKey]     = Eq.fromUniversalEquals
  given show: Show[MessageKey] = Show.show(_.key)

  /** The key for a bundle key text. */
  def fromKey(key: String): Option[MessageKey] = all.find(_.key == key)
}
