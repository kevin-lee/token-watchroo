package tokenwatchroo.core.providers

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*
import extras.render.Render
import refined4s.*
import refined4s.modules.cats.derivation.*
import refined4s.types.all.*
import tokenwatchroo.core.*

/** A Claude Team seat from the profile's `seat_tier`. */
enum TeamSeat derives CanEqual, Eq, Show {
  case Standard
  case Premium
}

object TeamSeat {
  def standard: TeamSeat = TeamSeat.Standard
  def premium: TeamSeat  = TeamSeat.Premium

  extension (seat: TeamSeat) {
    def wire: String = seat match {
      case TeamSeat.Standard => "team_standard"
      case TeamSeat.Premium => "team_tier_1"
    }

    def label: String = seat match {
      case TeamSeat.Standard => "Standard"
      case TeamSeat.Premium => "Premium"
    }
  }

  def parse(s: String): Either[String, TeamSeat] = s.trim.toLowerCase match {
    case "team_standard" => TeamSeat.Standard.asRight[String]
    case "team_tier_1" => TeamSeat.Premium.asRight[String]
    case unknown => s"Unknown TeamSeat: $unknown".asLeft[TeamSeat]
  }
}

/** The Max plan multiplier from a rate limit tier such as `default_claude_max_5x`. Never serialised: only the
  * `PlanLabel` built from it reaches a snapshot.
  */
type UsageMultiplier = UsageMultiplier.Type
object UsageMultiplier extends Newtype[PosInt], CatsEqShow[PosInt] {
  given render: Render[UsageMultiplier] = Render.render(m => s"${m.value.value}x")

  private val MaxToken = "max"

  /** The token right after `max` when it is one or more digits followed by `x`. */
  def fromRateLimitTier(tier: String): Option[UsageMultiplier] =
    tier.trim.toLowerCase.split('_').toList.dropWhile(_ =!= MaxToken) match {
      case _ :: candidate :: _ if isMultiplierToken(candidate) =>
        candidate.dropRight(1).toIntOption.flatMap(n => PosInt.from(n).toOption).map(UsageMultiplier(_))
      case _ => none[UsageMultiplier]
    }

  private def isMultiplierToken(token: String): Boolean =
    token.length > 1 && token.endsWith("x") && token.dropRight(1).forall(_.isDigit)
}

/** The Claude plan behind the badge. Max carries the usage multiplier when the tier names one, Team carries the seat. */
enum ClaudePlan derives CanEqual, Eq, Show {
  case Max(multiplier: Option[UsageMultiplier])
  case Pro
  case Team(seat: Option[TeamSeat])
  case Enterprise
}

object ClaudePlan {
  def max(multiplier: Option[UsageMultiplier]): ClaudePlan = ClaudePlan.Max(multiplier)
  def pro: ClaudePlan                                      = ClaudePlan.Pro
  def team(seat: Option[TeamSeat]): ClaudePlan             = ClaudePlan.Team(seat)
  def enterprise: ClaudePlan                               = ClaudePlan.Enterprise

  private val OrganizationPrefix = "claude_"

  /** The tier and the seat are payload sources only: a Pro word with a Max tier is Pro, a Max word with a seat is Max. */
  private def fromPlanWord(word: String, rateLimitTier: Option[String], seatTier: Option[String]): Option[ClaudePlan] =
    word.trim.toLowerCase match {
      case "max" => ClaudePlan.max(rateLimitTier.flatMap(UsageMultiplier.fromRateLimitTier)).some
      case "pro" => ClaudePlan.pro.some
      case "team" => ClaudePlan.team(seatTier.flatMap(s => TeamSeat.parse(s).toOption)).some
      case "enterprise" => ClaudePlan.enterprise.some
      case _ => none[ClaudePlan]
    }

  /** `claude_max`, `claude_pro`, `claude_team`, `claude_enterprise`. Anything else, including a missing prefix, is none. */
  def fromOrganizationType(
    organizationType: String,
    rateLimitTier: Option[String],
    seatTier: Option[String],
  ): Option[ClaudePlan] = {
    val normalized = organizationType.trim.toLowerCase
    Option
      .when(normalized.startsWith(OrganizationPrefix))(normalized.drop(OrganizationPrefix.length))
      .flatMap(fromPlanWord(_, rateLimitTier, seatTier))
  }

  /** `has_claude_max` wins (with the multiplier from the tier), then `has_claude_pro`, else none. */
  def fromAccount(account: ClaudeProfileAccount, rateLimitTier: Option[String]): Option[ClaudePlan] =
    if (account.hasClaudeMax.getOrElse(false)) {
      ClaudePlan.max(rateLimitTier.flatMap(UsageMultiplier.fromRateLimitTier)).some
    } else if (account.hasClaudePro.getOrElse(false)) {
      ClaudePlan.pro.some
    } else {
      none[ClaudePlan]
    }

  /** The keychain `subscriptionType` word. The tier is only a multiplier source and never overrides an unknown word. */
  def fromSubscriptionType(word: String, rateLimitTier: Option[String]): Option[ClaudePlan] =
    fromPlanWord(word, rateLimitTier, none[String])

  /** The tier alone: a `max`, `pro`, `team`, or `enterprise` token picks the plan, so `default_claude_ai` is none. */
  def fromRateLimitTier(rateLimitTier: String): Option[ClaudePlan] = {
    val tokens = rateLimitTier.trim.toLowerCase.split('_').toList
    if (tokens.contains("max")) {
      ClaudePlan.max(UsageMultiplier.fromRateLimitTier(rateLimitTier)).some
    } else if (tokens.contains("pro")) {
      ClaudePlan.pro.some
    } else if (tokens.contains("team")) {
      ClaudePlan.team(none[TeamSeat]).some
    } else if (tokens.contains("enterprise")) {
      ClaudePlan.enterprise.some
    } else {
      none[ClaudePlan]
    }
  }

  extension (plan: ClaudePlan) {

    /** Every branch starts with a non-empty literal, so `unsafeFrom` is total here. */
    def label: PlanLabel =
      PlanLabel(NonEmptyString.unsafeFrom(plan match {
        case ClaudePlan.Max(None) => "Max"
        case ClaudePlan.Max(Some(multiplier)) => s"Max ${UsageMultiplier.render.render(multiplier)}"
        case ClaudePlan.Pro => "Pro"
        case ClaudePlan.Team(None) => "Team"
        case ClaudePlan.Team(Some(seat)) => s"Team ${seat.label}"
        case ClaudePlan.Enterprise => "Enterprise"
      }))
  }
}
