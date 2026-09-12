package tokenwatchroo.core.providers

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*
import tokenwatchroo.core.*

/** One limit of the Claude OAuth usage response: `utilization` is 0 to 100, `resets_at` is ISO-8601. */
final case class ClaudeLimit(utilization: Option[Double], resetsAt: Option[String]) derives CanEqual, Eq, Show

/** `GET https://api.anthropic.com/api/oauth/usage`. Every field is optional because the endpoint is undocumented. */
final case class ClaudeUsageResponse(
  fiveHour: Option[ClaudeLimit],
  sevenDay: Option[ClaudeLimit],
  sevenDayOpus: Option[ClaudeLimit],
  sevenDaySonnet: Option[ClaudeLimit],
) derives CanEqual,
      Eq,
      Show

object ClaudeUsageResponse {

  val SessionLength: Seconds = Seconds(18000L)
  val WeeklyLength: Seconds  = Seconds(604800L)

  extension (response: ClaudeUsageResponse) {

    /** Session from `five_hour`, Weekly from `seven_day`. A missing limit or a null `resets_at` gives an idle window.
      * Per-model limits are ignored in v1.
      */
    def toWindows: Either[Iso8601Error, List[UsageWindow]] =
      for {
        session <- window(WindowId.Session, response.fiveHour, SessionLength)
        weekly  <- window(WindowId.Weekly, response.sevenDay, WeeklyLength)
      } yield List(session, weekly)
  }

  private def window(
    id: WindowId,
    limit: Option[ClaudeLimit],
    length: Seconds,
  ): Either[Iso8601Error, UsageWindow] =
    limit
      .flatMap(_.resetsAt)
      .traverse(Iso8601.parseToEpochSeconds)
      .map { resetsAt =>
        UsageWindow.clamped(id, limit.flatMap(_.utilization).getOrElse(0.0d), resetsAt, length.some)
      }
}
