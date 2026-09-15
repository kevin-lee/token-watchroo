package tokenwatchroo.core.providers

import cats.{Eq, Show}
import cats.derived.*
import tokenwatchroo.core.*

/** The `account` part of the profile. The flags are wire values of a payload record, like `ClaudeLimit.utilization`. */
final case class ClaudeProfileAccount(hasClaudeMax: Option[Boolean], hasClaudePro: Option[Boolean])
    derives CanEqual,
      Eq,
      Show

/** The `organization` part of the profile: the plan type, the rate limit tier, and the Team seat. */
final case class ClaudeProfileOrganization(
  organizationType: Option[String],
  rateLimitTier: Option[String],
  seatTier: Option[String],
) derives CanEqual,
      Eq,
      Show

object ClaudeProfileOrganization {
  extension (organization: ClaudeProfileOrganization) {
    def toPlan: Option[ClaudePlan] =
      organization
        .organizationType
        .flatMap(t => ClaudePlan.fromOrganizationType(t, organization.rateLimitTier, organization.seatTier))
  }
}

/** `GET https://api.anthropic.com/api/oauth/profile`, verified 2026-09-12. Only the badge fields are decoded, every
  * field is optional, and unknown fields are skipped.
  */
final case class ClaudeProfileResponse(
  account: Option[ClaudeProfileAccount],
  organization: Option[ClaudeProfileOrganization],
) derives CanEqual,
      Eq,
      Show

object ClaudeProfileResponse {
  extension (response: ClaudeProfileResponse) {

    /** The organization decides. The account flags are the fallback when the organization type is missing or unknown. */
    def toPlan: Option[ClaudePlan] =
      response
        .organization
        .flatMap(_.toPlan)
        .orElse(
          response.account.flatMap(a => ClaudePlan.fromAccount(a, response.organization.flatMap(_.rateLimitTier)))
        )

    def planLabel: Option[PlanLabel] = response.toPlan.map(_.label)
  }
}
