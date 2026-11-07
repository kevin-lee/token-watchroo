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

  /** The display order (#104): items whose agent has an entry in `config.agents` first, in the order of the first entry
    * for each agent, then the others in `AgentId` order. A stable sort, so items of the same agent keep their order.
    */
  def ordered[A](config: Config, items: List[A])(id: A => AgentId): List[A] =
    items.sortBy(a => rank(config, id(a)))

  /** Whether a config change needs new data (#104). A new order, plan or refresh interval does not: the next scheduled
    * tick shows them.
    */
  def needsFetch(old: Config, next: Config, ids: List[AgentId]): Boolean =
    old.codexHome =!= next.codexHome ||
      old.claudeCodeVersionOverride =!= next.claudeCodeVersionOverride ||
      ids.exists(id => !sameFetch(of(old, id), of(next, id)))

  /** Two `Fetch` choices are the same whatever their plan, because the plan only fills the badge. */
  private def sameFetch(a: AgentChoice, b: AgentChoice): Boolean = (a, b) match {
    case (AgentChoice.Fetch(_), AgentChoice.Fetch(_)) => true
    case _ => a === b
  }

  private def rank(config: Config, id: AgentId): (Int, Int) =
    (
      config.agents.indexWhere(_.id === id) match {
        case -1 => Int.MaxValue
        case i => i
      },
      AgentId.all.indexOf(id),
    )

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
