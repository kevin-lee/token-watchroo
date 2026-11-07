package tokenwatchroo.core

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*
import refined4s.types.all.*

/** What a refresh does with one agent, from `Config.agents`. */
enum AgentChoice derives CanEqual, Eq, Show {

  /** No entry for the agent: detect it, and fetch it when it is detected (the behaviour before #8). */
  case AutoDetect

  /** An enabled subscription entry: fetch without detection, so a signed-out agent shows why, then apply the plan. */
  case Fetch(plan: PlanKind)

  /** An enabled API entry: an unavailable card, the provider is not called. */
  case ApiNotSupported

  /** A disabled entry: no card. */
  case Hidden
}

object AgentChoice {
  def autoDetect: AgentChoice            = AgentChoice.AutoDetect
  def fetch(plan: PlanKind): AgentChoice = AgentChoice.Fetch(plan)
  def apiNotSupported: AgentChoice       = AgentChoice.ApiNotSupported
  def hidden: AgentChoice                = AgentChoice.Hidden

  val ApiNotSupportedMessage: ErrorMessage = ErrorMessage(NonEmptyString("API budget agents are not supported yet"))

  /** The first entry for `id` is used. Other entries with the same `id` are ignored. */
  def of(config: Config, id: AgentId): AgentChoice =
    config.agents.find(_.id === id) match {
      case None => AgentChoice.autoDetect
      case Some(agent) =>
        agent.enabled match {
          case Enabled.Off => AgentChoice.hidden
          case Enabled.On =>
            agent.billing match {
              case Billing.Api => AgentChoice.apiNotSupported
              case Billing.Subscription => AgentChoice.fetch(agent.plan)
            }
        }
    }
}
