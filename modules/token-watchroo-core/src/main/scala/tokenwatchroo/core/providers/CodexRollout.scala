package tokenwatchroo.core.providers

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*
import com.github.plokhotnyuk.jsoniter_scala.macros.named
import tokenwatchroo.core.*
import tokenwatchroo.core.codecs.given

/** One window inside a `token_count` event of a Codex rollout log. `resets_at` is epoch seconds. */
final case class CodexRolloutWindow(
  usedPercent: Option[Double],
  windowMinutes: Option[Long],
  resetsAt: Option[Long],
) derives CanEqual,
      Eq,
      Show

final case class CodexRolloutRateLimits(
  primary: Option[CodexRolloutWindow],
  secondary: Option[CodexRolloutWindow],
  planType: Option[String],
) derives CanEqual,
      Eq,
      Show

object CodexRolloutRateLimits {
  extension (rateLimits: CodexRolloutRateLimits) {
    def toWindows: List[UsageWindow] =
      List(
        window(WindowId.Session, rateLimits.primary),
        window(WindowId.Weekly, rateLimits.secondary),
      )

    def planLabel: Option[PlanLabel] = rateLimits.planType.flatMap(PlanLabel.fromPlanType)
  }

  private def window(id: WindowId, rolloutWindow: Option[CodexRolloutWindow]): UsageWindow =
    UsageWindow.clamped(
      id,
      rolloutWindow.flatMap(_.usedPercent).getOrElse(0.0d),
      rolloutWindow.flatMap(_.resetsAt).map(EpochSeconds(_)),
      rolloutWindow.flatMap(_.windowMinutes).map(minutes => Seconds(minutes * 60L)),
    )
}

final case class CodexRolloutPayload(
  @named("type") kind: Option[String],
  rateLimits: Option[CodexRolloutRateLimits],
) derives CanEqual,
      Eq,
      Show

/** One line of a Codex rollout log under `~/.codex/sessions`. */
final case class CodexRolloutLine(
  timestamp: Option[String],
  @named("type") kind: Option[String],
  payload: Option[CodexRolloutPayload],
) derives CanEqual,
      Eq,
      Show

/** The rate limits of one `token_count` line and the line's own `timestamp`. */
final case class CodexRolloutEvent(
  rateLimits: CodexRolloutRateLimits,
  observedAt: Option[EpochSeconds],
) derives CanEqual,
      Eq,
      Show

object CodexRollout {

  /** The last `event_msg` line whose `token_count` payload carries `rate_limits`, as an event. Malformed lines are
    * skipped.
    */
  def latestEvent(lines: Iterator[String]): Option[CodexRolloutEvent] =
    lines.foldLeft(none[CodexRolloutEvent])(withLine)

  /** One step of [[latestEvent]]: the event of `line` if it carries rate limits, else `latest`. A caller that streams a
    * log folds with it, so the log is never held in memory whole (#65).
    */
  def withLine(latest: Option[CodexRolloutEvent], line: String): Option[CodexRolloutEvent] =
    eventOf(line).orElse(latest)

  private def eventOf(line: String): Option[CodexRolloutEvent] =
    codecs
      .readEither[CodexRolloutLine](line)
      .toOption
      .filter(_.kind.contains("event_msg"))
      .flatMap { rolloutLine =>
        rolloutLine
          .payload
          .filter(_.kind.contains("token_count"))
          .flatMap(_.rateLimits)
          .map(rateLimits =>
            CodexRolloutEvent(rateLimits, rolloutLine.timestamp.flatMap(Iso8601.parseToEpochSeconds(_).toOption))
          )
      }
}
