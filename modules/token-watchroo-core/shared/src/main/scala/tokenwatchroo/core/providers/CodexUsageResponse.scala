package tokenwatchroo.core.providers

import cats.{Eq, Show}
import cats.derived.*
import tokenwatchroo.core.*

/** One window of the Codex usage response. `resets_at` is epoch seconds. */
final case class CodexWindow(
  usedPercent: Option[Double],
  limitWindowSeconds: Option[Long],
  resetsAt: Option[Long],
) derives CanEqual,
      Eq,
      Show

final case class CodexRateLimit(
  primaryWindow: Option[CodexWindow],
  secondaryWindow: Option[CodexWindow],
) derives CanEqual,
      Eq,
      Show

/** `GET https://chatgpt.com/backend-api/wham/usage`. Every field is optional because the endpoint is undocumented. */
final case class CodexUsageResponse(
  rateLimit: Option[CodexRateLimit],
  planType: Option[String],
) derives CanEqual,
      Eq,
      Show

object CodexUsageResponse {

  extension (response: CodexUsageResponse) {

    /** Session from the primary window, Weekly from the secondary window. */
    def toWindows: List[UsageWindow] =
      List(
        window(WindowId.Session, response.rateLimit.flatMap(_.primaryWindow)),
        window(WindowId.Weekly, response.rateLimit.flatMap(_.secondaryWindow)),
      )

    def planLabel: Option[PlanLabel] = response.planType.flatMap(PlanLabel.fromPlanType)
  }

  private def window(id: WindowId, codexWindow: Option[CodexWindow]): UsageWindow =
    UsageWindow.clamped(
      id,
      codexWindow.flatMap(_.usedPercent).getOrElse(0.0d),
      codexWindow.flatMap(_.resetsAt).map(EpochSeconds(_)),
      codexWindow.flatMap(_.limitWindowSeconds).map(Seconds(_)),
    )
}
