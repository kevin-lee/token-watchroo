package tokenwatchroo.core

import cats.syntax.all.*

/** Text helpers for notification copy. Short menu labels and local times are rendered by the Swift shell. */
object Format {

  /** "72%" */
  def percent(p: UsedPercent): String = s"${math.round(p.value)}%"

  /** "under a minute", "11 minutes", "1 hour 12 minutes", "2 days 3 hours" */
  def longDuration(seconds: Seconds): String = {
    val total   = math.max(0L, seconds.value)
    val days    = total / 86400L
    val hours   = (total % 86400L) / 3600L
    val minutes = (total % 3600L) / 60L
    if (total < 60L) "under a minute"
    else if (days > 0L) joinUnits(unit(days, "day"), unit(hours, "hour"))
    else if (hours > 0L) joinUnits(unit(hours, "hour"), unit(minutes, "minute"))
    else unit(minutes, "minute")
  }

  private def unit(n: Long, name: String): String = if (n === 1L) s"1 $name" else s"$n ${name}s"

  private def joinUnits(major: String, minor: String): String =
    if (minor.startsWith("0 ")) major else s"$major $minor"
}
