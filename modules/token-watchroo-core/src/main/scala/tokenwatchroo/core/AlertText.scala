package tokenwatchroo.core

import cats.syntax.all.*
import extras.render.syntax.*

/** Notification copy, following `.ai/docs/design/ui/Alerts.dc.html`. Per-model rows read "Claude Code is near its
  * weekly Fable limit" and "68% of the weekly Fable window used. Resets in 2 hours." Spend rows (#105) read "Codex is
  * near its monthly budget" and "$246.00 of $300.00 spent, 82%. Resets in 19 days."
  */
object AlertText {

  /** "is almost out" when the window is in the red band (#9), so the title and the "Almost out" pill always agree. */
  def level(agent: AgentId, window: UsageWindow, now: EpochSeconds, level: AlertLevel): Alert = {
    val title =
      if (UsageBand.of(window.usedPercent) === UsageBand.Critical) render"$agent is almost out"
      else render"$agent is near its ${window.id.limitNoun} limit"
    build(agent, window, now, AlertKind.level(level), title, usageBody(window, now))
  }

  def reset(agent: AgentId, window: UsageWindow, now: EpochSeconds): Alert =
    build(
      agent,
      window,
      now,
      AlertKind.Reset,
      render"$agent ${window.id.limitNoun} reset",
      s"A fresh ${window.id.describe} is available.",
    )

  /** A spend level alert (#105). "is almost out of its ..." in the red band, as for a window. */
  def spendLevel(agent: AgentId, spend: Spend, now: EpochSeconds, kind: SpendAlertKind): Alert = {
    val title   =
      if (UsageBand.of(spend.usedPercent) === UsageBand.Critical)
        render"$agent is almost out of its ${spendOutNoun(spend.currency)}"
      else render"$agent is near its ${spendNoun(spend.currency)}"
    val summary = s"${Format.spendSummary(spend)}, ${Format.percent(spend.usedPercent)}."
    val body    = spend
      .resetsAt
      .map(resetsAt => Seconds.clampNonNegative(now.secondsUntil(resetsAt)))
      .fold(summary)(remaining => render"$summary Resets in $remaining.")
    Alert(agent, AlertSubject.spend(kind, spend.resetsAt), spend.usedPercent, title, body)
  }

  /** The spend meter started again (#105). */
  def spendReset(agent: AgentId, spend: Spend): Alert = {
    val (title, body) = spend.currency match {
      case Currency.Iso(_) => (render"$agent budget reset", "A fresh monthly budget is available.")
      case Currency.Credits => (render"$agent credits reset", "The credit limit is available again.")
    }
    Alert(agent, AlertSubject.spend(SpendAlertKind.Reset, spend.resetsAt), spend.usedPercent, title, body)
  }

  private def spendNoun(currency: Currency): String = currency match {
    case Currency.Iso(_) => "monthly budget"
    case Currency.Credits => "credit limit"
  }

  private def spendOutNoun(currency: Currency): String = currency match {
    case Currency.Iso(_) => "monthly budget"
    case Currency.Credits => "credits"
  }

  private def usageBody(window: UsageWindow, now: EpochSeconds): String = {
    val used = render"${window.usedPercent} of the ${window.id.describe} used."
    window.resetsIn(now).fold(used)(remaining => render"$used Resets in $remaining.")
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
