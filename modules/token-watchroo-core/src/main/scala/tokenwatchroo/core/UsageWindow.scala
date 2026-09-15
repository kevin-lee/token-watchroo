package tokenwatchroo.core

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*

/** One usage window of an agent. A window without `resetsAt` is idle: no active window is running. */
final case class UsageWindow(
  id: WindowId,
  usedPercent: UsedPercent,
  resetsAt: Option[EpochSeconds],
  windowLength: Option[Seconds],
) derives CanEqual,
      Eq,
      Show

object UsageWindow {

  def clamped(
    id: WindowId,
    usedPercent: Double,
    resetsAt: Option[EpochSeconds],
    windowLength: Option[Seconds],
  ): UsageWindow =
    UsageWindow(id, UsedPercent.clamp(usedPercent), resetsAt, windowLength)

  extension (window: UsageWindow) {
    def resetsIn(now: EpochSeconds): Option[Seconds] =
      window.resetsAt.map(resetsAt => Seconds.clampNonNegative(now.secondsUntil(resetsAt)))

    def isIdle: Boolean = window.resetsAt.isEmpty

    def isExhausted: Boolean = window.usedPercent >= UsedPercent.clamp(100.0d)
  }
}
