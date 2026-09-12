package tokenwatchroo.core.providers

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*
import refined4s.types.all.*
import tokenwatchroo.core.*

/** One limit of the Claude OAuth usage response: `utilization` is 0 to 100, `resets_at` is ISO-8601. */
final case class ClaudeLimit(utilization: Option[Double], resetsAt: Option[String]) derives CanEqual, Eq, Show

/** The model behind a scoped entry of `limits[]`. */
final case class ClaudeLimitModel(id: Option[String], displayName: Option[String]) derives CanEqual, Eq, Show

/** The `scope` of a `limits[]` entry. */
final case class ClaudeLimitScope(model: Option[ClaudeLimitModel], surface: Option[String]) derives CanEqual, Eq, Show

/** One entry of `limits[]`, verified 2026-09-12: `percent` is an integer on the wire, `resets_at` is ISO-8601 with
  * microseconds, `scope.model.id` and `scope.surface` were null, and `is_active` was true only on the scoped entry,
  * so it is not a running flag. `group`, `severity`, and `is_active` are decoded and unused.
  */
final case class ClaudeLimitEntry(
  kind: Option[String],
  group: Option[String],
  percent: Option[Double],
  severity: Option[String],
  resetsAt: Option[String],
  scope: Option[ClaudeLimitScope],
  isActive: Option[Boolean],
) derives CanEqual,
      Eq,
      Show

object ClaudeLimitEntry {
  extension (entry: ClaudeLimitEntry) {
    def isWeeklyScoped: Boolean =
      entry.kind.flatMap(k => ClaudeLimitKind.parse(k).toOption).exists(_ === ClaudeLimitKind.WeeklyScoped)

    def modelName: Option[ModelName] =
      entry.scope.flatMap(_.model).flatMap(_.displayName).flatMap(ModelName.fromDisplayName)
  }
}

/** The kind of a `limits[]` entry. Unknown kinds decode as a plain string and are ignored by the mapping. */
enum ClaudeLimitKind derives CanEqual, Eq, Show {
  case Session
  case WeeklyAll
  case WeeklyScoped
}

object ClaudeLimitKind {
  def session: ClaudeLimitKind      = ClaudeLimitKind.Session
  def weeklyAll: ClaudeLimitKind    = ClaudeLimitKind.WeeklyAll
  def weeklyScoped: ClaudeLimitKind = ClaudeLimitKind.WeeklyScoped

  extension (kind: ClaudeLimitKind) {
    def wire: String = kind match {
      case ClaudeLimitKind.Session => "session"
      case ClaudeLimitKind.WeeklyAll => "weekly_all"
      case ClaudeLimitKind.WeeklyScoped => "weekly_scoped"
    }
  }

  def parse(s: String): Either[String, ClaudeLimitKind] = s.trim.toLowerCase match {
    case "session" => ClaudeLimitKind.Session.asRight[String]
    case "weekly_all" => ClaudeLimitKind.WeeklyAll.asRight[String]
    case "weekly_scoped" => ClaudeLimitKind.WeeklyScoped.asRight[String]
    case unknown => s"Unknown ClaudeLimitKind: $unknown".asLeft[ClaudeLimitKind]
  }
}

/** `GET https://api.anthropic.com/api/oauth/usage`. Every field is optional because the endpoint is undocumented.
  * `limits` is the primary source of per-model windows, the fixed `seven_day_opus` and `seven_day_sonnet` keys the
  * fallback when it is absent or null.
  */
final case class ClaudeUsageResponse(
  fiveHour: Option[ClaudeLimit],
  sevenDay: Option[ClaudeLimit],
  sevenDayOpus: Option[ClaudeLimit],
  sevenDaySonnet: Option[ClaudeLimit],
  limits: Option[List[ClaudeLimitEntry]],
) derives CanEqual,
      Eq,
      Show

object ClaudeUsageResponse {

  val SessionLength: Seconds = Seconds(18000L)
  val WeeklyLength: Seconds  = Seconds(604800L)

  private val OpusName: ModelName   = ModelName(NonEmptyString("Opus"))
  private val SonnetName: ModelName = ModelName(NonEmptyString("Sonnet"))

  /** A per-model row before its reset time is parsed. */
  final private case class ModelCandidate(name: ModelName, percent: Double, resetsAt: String)

  extension (response: ClaudeUsageResponse) {

    /** Session from `five_hour`, Weekly from `seven_day`, idle when missing or without a reset. Per-model rows come
      * from `limits` when it is present, else from the fixed keys, only with a `resets_at`, the first entry wins for a
      * repeated name, sorted by name.
      */
    def toWindows: Either[Iso8601Error, List[UsageWindow]] =
      for {
        session <- window(WindowId.Session, response.fiveHour, SessionLength)
        weekly  <- window(WindowId.Weekly, response.sevenDay, WeeklyLength)
        models  <- modelWindows(response)
      } yield session :: weekly :: models
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

  private def modelWindows(response: ClaudeUsageResponse): Either[Iso8601Error, List[UsageWindow]] =
    response.limits match {
      case Some(entries) => build(scopedCandidates(entries))
      case None => build(fixedCandidates(response))
    }

  private def scopedCandidates(entries: List[ClaudeLimitEntry]): List[ModelCandidate] =
    entries
      .filter(_.isWeeklyScoped)
      .flatMap { entry =>
        (entry.modelName, entry.resetsAt).mapN { (name, resetsAt) =>
          ModelCandidate(name, entry.percent.getOrElse(0.0d), resetsAt)
        }
      }
      .distinctBy(_.name)

  private def fixedCandidates(response: ClaudeUsageResponse): List[ModelCandidate] =
    List(OpusName -> response.sevenDayOpus, SonnetName -> response.sevenDaySonnet).flatMap {
      case (name, limit) =>
        limit.flatMap { l =>
          l.resetsAt.map(resetsAt => ModelCandidate(name, l.utilization.getOrElse(0.0d), resetsAt))
        }
    }

  private def build(candidates: List[ModelCandidate]): Either[Iso8601Error, List[UsageWindow]] =
    candidates
      .sortBy(_.name.value.value)
      .traverse { candidate =>
        Iso8601
          .parseToEpochSeconds(candidate.resetsAt)
          .map(epoch =>
            UsageWindow.clamped(WindowId.model(candidate.name), candidate.percent, epoch.some, WeeklyLength.some)
          )
      }
}
