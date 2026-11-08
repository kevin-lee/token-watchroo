package tokenwatchroo.core

import cats.syntax.all.*

/** Notification copy, following `.ai/docs/design/ui/Alerts.dc.html`, in the language of `messages` (#10). Per-model
  * rows read "Claude Code is near its weekly Fable limit" and "68% of the weekly Fable window used. Resets in 2 hours."
  * Spend rows (#105) read "Codex is near its monthly budget" and "$246.00 of $300.00 spent, 82%. Resets in 19 days."
  * The parts of a body are joined with one space in every language.
  */
object AlertText {

  /** "is almost out" when the window is in the red band (#9), so the title and the "Almost out" pill always agree. */
  def level(agent: AgentId, window: UsageWindow, now: EpochSeconds, level: AlertLevel, messages: Messages): Alert = {
    val title =
      if (UsageBand.of(window.usedPercent) === UsageBand.Critical) messages.alertTitleAlmostOut(agent)
      else messages.alertTitleNear(agent, window.id)
    build(agent, window, now, AlertKind.level(level), title, usageBody(window, now, messages))
  }

  def reset(agent: AgentId, window: UsageWindow, now: EpochSeconds, messages: Messages): Alert =
    build(
      agent,
      window,
      now,
      AlertKind.Reset,
      messages.alertTitleReset(agent, window.id),
      messages.alertBodyFresh(window.id),
    )

  /** A spend level alert (#105). "is almost out of its ..." in the red band, as for a window. */
  def spendLevel(agent: AgentId, spend: Spend, now: EpochSeconds, kind: SpendAlertKind, messages: Messages): Alert = {
    val title   =
      if (UsageBand.of(spend.usedPercent) === UsageBand.Critical)
        messages.alertTitleSpendAlmostOut(agent, spend.currency)
      else messages.alertTitleSpendNear(agent, spend.currency)
    val summary = messages.alertBodySpend(spend)
    val body    = spend
      .resetsAt
      .map(resetsAt => Seconds.clampNonNegative(now.secondsUntil(resetsAt)))
      .fold(summary)(remaining => s"$summary ${resetsIn(remaining, messages)}")
    Alert(agent, AlertSubject.spend(kind, spend.resetsAt), spend.usedPercent, title, body)
  }

  /** The spend meter started again (#105). */
  def spendReset(agent: AgentId, spend: Spend, messages: Messages): Alert =
    Alert(
      agent,
      AlertSubject.spend(SpendAlertKind.Reset, spend.resetsAt),
      spend.usedPercent,
      messages.alertTitleSpendReset(agent, spend.currency),
      messages.alertBodySpendReset(spend.currency),
    )

  /** "Resets in 11 minutes.", or "Resets in under a minute." under 60 s, because Korean words it differently. */
  private def resetsIn(remaining: Seconds, messages: Messages): String =
    if (remaining.value < 60L) messages.alertResetsSoon else messages.alertResetsIn(remaining)

  private def usageBody(window: UsageWindow, now: EpochSeconds, messages: Messages): String = {
    val used = messages.alertBodyUsed(window.usedPercent, window.id)
    window.resetsIn(now).fold(used)(remaining => s"$used ${resetsIn(remaining, messages)}")
  }

  private def build(
    agent: AgentId,
    window: UsageWindow,
    now: EpochSeconds,
    kind: AlertKind,
    title: String,
    body: String,
  ): Alert =
    Alert(
      agent,
      AlertSubject.window(window.id, kind, window.resetsAt.getOrElse(now)),
      window.usedPercent,
      title,
      body,
    )
}
