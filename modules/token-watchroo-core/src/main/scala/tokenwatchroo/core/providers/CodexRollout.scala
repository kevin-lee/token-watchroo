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
  @named("type") kind: Option[String],
  payload: Option[CodexRolloutPayload],
) derives CanEqual,
      Eq,
      Show

object CodexRollout {

  /** The last `event_msg` line whose `token_count` payload carries `rate_limits`. Malformed lines are skipped. */
  def latestRateLimits(lines: Iterator[String]): Option[CodexRolloutRateLimits] =
    lines.foldLeft(none[CodexRolloutRateLimits])(withLine)

  /** One step of [[latestRateLimits]]: the rate limits of `line` if it carries any, else `latest`. A caller that
    * streams a log folds with it, so the log is never held in memory whole (#65).
    */
  def withLine(latest: Option[CodexRolloutRateLimits], line: String): Option[CodexRolloutRateLimits] =
    rateLimitsOf(line).orElse(latest)

  private def rateLimitsOf(line: String): Option[CodexRolloutRateLimits] =
    codecs
      .readEither[CodexRolloutLine](line)
      .toOption
      .filter(_.kind.contains("event_msg"))
      .flatMap(_.payload)
      .filter(_.kind.contains("token_count"))
      .flatMap(_.rateLimits)
}
