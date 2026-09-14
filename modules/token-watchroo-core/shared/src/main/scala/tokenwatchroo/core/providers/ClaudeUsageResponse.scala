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

/** An amount of the usage response's `spend`: 5 minor units at exponent 2 in USD is $0.05. */
final case class ClaudeMoney(amountMinor: Option[Long], currency: Option[String], exponent: Option[Int])
    derives CanEqual,
      Eq,
      Show

/** The usage response's `spend`, verified 2026-09-14 (issue #33). A usage-based Enterprise account had `used`
  * {5, USD, 2}, `limit` {20000, USD, 2}, and `enabled` true, matching "$0.05 of $200.00" on the Claude usage page. A Max
  * account had `limit` null and `enabled` false. `percent` is a rounded integer and is not decoded, and neither are
  * `severity`, `cap`, `balance`, and the rest.
  */
final case class ClaudeSpend(used: Option[ClaudeMoney], limit: Option[ClaudeMoney], enabled: Option[Boolean])
    derives CanEqual,
      Eq,
      Show

/** `GET https://api.anthropic.com/api/oauth/usage`. Every field is optional because the endpoint is undocumented.
  * `limits` is the primary source of per-model windows, the fixed `seven_day_opus` and `seven_day_sonnet` keys the
  * fallback when it is absent or null. `spend` carries the monthly spend limit of a usage-based Enterprise plan.
  */
final case class ClaudeUsageResponse(
  fiveHour: Option[ClaudeLimit],
  sevenDay: Option[ClaudeLimit],
  sevenDayOpus: Option[ClaudeLimit],
  sevenDaySonnet: Option[ClaudeLimit],
  limits: Option[List[ClaudeLimitEntry]],
  spend: Option[ClaudeSpend],
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

    /** The layout comes from the response, not the plan. When `five_hour` and `seven_day` are both absent or null,
      * the meters are the per-model rows plus the spend when `spend` is enabled with a `used` and a `limit`, else no
      * spend. The response has no reset time, so the spend resets at the start of the next UTC month after `now`.
      * When either window key is present, the meters are `toWindows` and no spend.
      */
    def toMeters(now: EpochSeconds): Either[DecodeError, UsageMeters] =
      if (response.fiveHour.isEmpty && response.sevenDay.isEmpty) {
        for {
          models <- modelWindows(response).leftMap(timestampError)
          spend  <- usableSpend(response).traverse { case (used, limit) => toSpend(used, limit, now) }
        } yield UsageMeters(models, spend)
      } else {
        response.toWindows.leftMap(timestampError).map(windows => UsageMeters(windows, none[Spend]))
      }
  }

  /** The parts of a `ClaudeMoney` that must all be present. */
  final private case class MoneyParts(minor: Long, currency: String, exponent: Int)

  private def timestampError(error: Iso8601Error): DecodeError = DecodeError.invalid(error.message)

  private def usableSpend(response: ClaudeUsageResponse): Option[(ClaudeMoney, ClaudeMoney)] =
    response.spend.filter(_.enabled.exists(identity)).flatMap(spend => (spend.used, spend.limit).tupled)

  private def moneyParts(money: ClaudeMoney): Either[DecodeError, MoneyParts] =
    (money.amountMinor, money.currency, money.exponent)
      .mapN(MoneyParts.apply)
      .toRight(DecodeError.invalid("Spend is missing amount_minor, exponent, or currency"))

  private def toSpend(used: ClaudeMoney, limit: ClaudeMoney, now: EpochSeconds): Either[DecodeError, Spend] =
    for {
      usedParts   <- moneyParts(used)
      limitParts  <- moneyParts(limit)
      _           <- Either.cond(
                       usedParts.currency === limitParts.currency,
                       (),
                       DecodeError.invalid(s"Spend currencies differ: ${usedParts.currency} and ${limitParts.currency}"),
                     )
      currency    <- Currency.parse(usedParts.currency).leftMap(DecodeError.invalid)
      spent       <- Amount.fromMinorUnits(usedParts.minor, usedParts.exponent).leftMap(DecodeError.invalid)
      limitAmount <- Amount.fromMinorUnits(limitParts.minor, limitParts.exponent).leftMap(DecodeError.invalid)
    } yield Spend(currency, spent, limitAmount, Iso8601.startOfNextUtcMonth(now).some)

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
