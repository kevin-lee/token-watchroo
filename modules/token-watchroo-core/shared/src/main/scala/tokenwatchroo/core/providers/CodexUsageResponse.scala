package tokenwatchroo.core.providers

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*
import refined4s.*
import refined4s.modules.cats.derivation.*
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

/** An amount as the provider sent it, such as "17000.50". A JSON number is kept as its decimal text. */
type DecimalText = DecimalText.Type
object DecimalText extends Newtype[String], CatsEqShow[String]

/** The credit balance. Decoded and not used. */
final case class CodexCredits(
  hasCredits: Option[Boolean],
  unlimited: Option[Boolean],
  balance: Option[DecimalText],
) derives CanEqual,
      Eq,
      Show

/** The per-user credit limit. `reset_at` is epoch seconds. */
final case class CodexIndividualLimit(
  limit: Option[DecimalText],
  used: Option[DecimalText],
  remaining: Option[DecimalText],
  usedPercent: Option[Double],
  resetAt: Option[Long],
) derives CanEqual,
      Eq,
      Show

final case class CodexSpendControl(
  reached: Option[Boolean],
  individualLimit: Option[CodexIndividualLimit],
) derives CanEqual,
      Eq,
      Show

/** `GET https://chatgpt.com/backend-api/wham/usage`. Every field is optional because the endpoint is undocumented.
  * `credits` and `spend_control` are UNVERIFIED (issue #33): the shape is from the public report in
  * alondero/buildmesh#1684 for Business and Enterprise, where `rate_limit` is null.
  */
final case class CodexUsageResponse(
  rateLimit: Option[CodexRateLimit],
  planType: Option[String],
  credits: Option[CodexCredits],
  spendControl: Option[CodexSpendControl],
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

    /** With `rate_limit` the windows and no spend. Without it no windows, and a spend in credits from
      * `spend_control.individual_limit` when it has both `used` and `limit`, else no spend.
      */
    def toMeters: Either[DecodeError, UsageMeters] =
      response.rateLimit match {
        case Some(_) => UsageMeters(response.toWindows, none[Spend]).asRight[DecodeError]
        case None => individualSpend(response).map(spend => UsageMeters(Nil, spend))
      }

    def planLabel: Option[PlanLabel] = response.planType.flatMap(PlanLabel.fromPlanType)
  }

  private def window(id: WindowId, codexWindow: Option[CodexWindow]): UsageWindow =
    UsageWindow.clamped(
      id,
      codexWindow.flatMap(_.usedPercent).getOrElse(0.0d),
      codexWindow.flatMap(_.resetsAt).map(EpochSeconds(_)),
      codexWindow.flatMap(_.limitWindowSeconds).map(Seconds(_)),
    )

  private def individualSpend(response: CodexUsageResponse): Either[DecodeError, Option[Spend]] =
    response
      .spendControl
      .flatMap(_.individualLimit)
      .flatMap(limit => (limit.used, limit.limit).mapN((used, cap) => (used, cap, limit.resetAt)))
      .traverse {
        case (used, cap, resetAt) =>
          for {
            spent <- amount(used)
            total <- amount(cap)
          } yield Spend(Currency.credits, spent, total, resetAt.map(EpochSeconds(_)))
      }

  private def amount(text: DecimalText): Either[DecodeError, Amount] =
    Amount.fromDecimalString(text.value).leftMap(DecodeError.invalid)
}
