package tokenwatchroo.core

import extras.render.syntax.*

/** Notification copy, following `design/Alerts.dc.html`. Per-model rows read "Claude Code is near its weekly Fable
  * limit" and "68% of the weekly Fable window used. Resets in 2 hours."
  */
object AlertText {

  def warning80(agent: AgentId, window: UsageWindow, now: EpochSeconds): Alert =
    build(
      agent,
      window,
      now,
      AlertKind.Warning80,
      render"$agent is near its ${window.id.limitNoun} limit",
      usageBody(window, now)
    )

  def critical95(agent: AgentId, window: UsageWindow, now: EpochSeconds): Alert =
    build(agent, window, now, AlertKind.Critical95, render"$agent is almost out", usageBody(window, now))

  def reset(agent: AgentId, window: UsageWindow, now: EpochSeconds): Alert =
    build(
      agent,
      window,
      now,
      AlertKind.Reset,
      render"$agent ${window.id.limitNoun} reset",
      s"A fresh ${window.id.describe} is available.",
    )

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
    Alert(agent, window.id, kind, window.resetsAt.getOrElse(now), window.usedPercent, title, body)
}
