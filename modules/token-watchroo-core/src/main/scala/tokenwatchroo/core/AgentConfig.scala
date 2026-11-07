package tokenwatchroo.core

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*
import refined4s.types.all.*

/** How an agent is paid for, as the user chose it in the Add-agent sheet. `Api` agents are not supported yet (#14). */
enum Billing derives CanEqual, Eq, Show {
  case Subscription
  case Api
}

object Billing {
  def subscription: Billing = Billing.Subscription
  def api: Billing          = Billing.Api

  /** Used when a config entry has no `billing`. */
  val default: Billing = Billing.Subscription

  extension (billing: Billing) {
    def wire: String = billing match {
      case Billing.Subscription => "subscription"
      case Billing.Api => "api"
    }
  }

  def parse(s: String): Either[String, Billing] = s match {
    case "subscription" => Billing.Subscription.asRight[String]
    case "api" => Billing.Api.asRight[String]
    case unknown => s"Unknown Billing: $unknown".asLeft[Billing]
  }
}

/** The plan the user says an agent is on. It is not the plan that a provider reads (`ClaudePlan` or the Codex
  * `plan_type`): it only fills the badge when the provider has none, see `badge`.
  */
enum PlanKind derives CanEqual, Eq, Show {
  case Individual
  case Team
  case Enterprise
}

object PlanKind {
  def individual: PlanKind = PlanKind.Individual
  def team: PlanKind       = PlanKind.Team
  def enterprise: PlanKind = PlanKind.Enterprise

  /** Used when a config entry has no `plan`. */
  val default: PlanKind = PlanKind.Individual

  private val TeamLabel: PlanLabel       = PlanLabel(NonEmptyString("Team"))
  private val EnterpriseLabel: PlanLabel = PlanLabel(NonEmptyString("Enterprise"))

  extension (plan: PlanKind) {
    def wire: String = plan match {
      case PlanKind.Individual => "individual"
      case PlanKind.Team => "team"
      case PlanKind.Enterprise => "enterprise"
    }

    /** The badge for a card. The provider's label always wins, because it is the account's real plan and the user's
      * choice can be wrong (#8). Without a provider label, `Team` and `Enterprise` fill the badge and `Individual`
      * leaves it empty, because only the provider knows the individual tier (Pro, Max 5x, Plus).
      */
    def badge(provider: Option[PlanLabel]): Option[PlanLabel] = provider.orElse(plan.label)

    private def label: Option[PlanLabel] = plan match {
      case PlanKind.Individual => none[PlanLabel]
      case PlanKind.Team => TeamLabel.some
      case PlanKind.Enterprise => EnterpriseLabel.some
    }
  }

  def parse(s: String): Either[String, PlanKind] = s match {
    case "individual" => PlanKind.Individual.asRight[String]
    case "team" => PlanKind.Team.asRight[String]
    case "enterprise" => PlanKind.Enterprise.asRight[String]
    case unknown => s"Unknown PlanKind: $unknown".asLeft[PlanKind]
  }
}

/** Whether a configured agent is shown. A JSON boolean on the wire. */
enum Enabled derives CanEqual, Eq, Show {
  case On
  case Off
}

object Enabled {
  def on: Enabled  = Enabled.On
  def off: Enabled = Enabled.Off

  /** Used when a config entry has no `enabled`. */
  val default: Enabled = Enabled.On

  def fromBoolean(value: Boolean): Enabled = if (value) Enabled.On else Enabled.Off

  extension (enabled: Enabled) {
    def isOn: Boolean = enabled match {
      case Enabled.On => true
      case Enabled.Off => false
    }
  }
}

/** One entry of `Config.agents`, written by the settings window of the Swift shell. `alertLevels` are the window alert
  * levels of the agent (#9).
  */
final case class AgentConfig(id: AgentId, billing: Billing, plan: PlanKind, enabled: Enabled, alertLevels: AlertLevels)
    derives CanEqual,
      Eq,
      Show

object AgentConfig {

  /** The values that an entry with only an `id` decodes to. */
  def defaults(id: AgentId): AgentConfig =
    AgentConfig(id, Billing.default, PlanKind.default, Enabled.default, AlertLevels.defaults)
}
