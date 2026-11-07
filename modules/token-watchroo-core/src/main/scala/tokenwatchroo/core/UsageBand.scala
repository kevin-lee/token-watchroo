package tokenwatchroo.core

import cats.{Eq, Show}
import cats.derived.*

/** The colour band of a percent (#101). Exhausted is not a band: the status and the menubar check it first. */
enum UsageBand derives CanEqual, Eq, Show {
  case Normal
  case Elevated
  case Warning
  case Critical
}

object UsageBand {
  def normal: UsageBand   = UsageBand.Normal
  def elevated: UsageBand = UsageBand.Elevated
  def warning: UsageBand  = UsageBand.Warning
  def critical: UsageBand = UsageBand.Critical

  def of(p: UsedPercent): UsageBand = {
    val shown = Format.wholePercent(p)
    if (shown > Thresholds.critical) UsageBand.Critical
    else if (shown > Thresholds.warning) UsageBand.Warning
    else if (shown > Thresholds.elevated) UsageBand.Elevated
    else UsageBand.Normal
  }

  extension (band: UsageBand) {
    def isAtLeast(other: UsageBand): Boolean = band.ordinal >= other.ordinal
  }
}
