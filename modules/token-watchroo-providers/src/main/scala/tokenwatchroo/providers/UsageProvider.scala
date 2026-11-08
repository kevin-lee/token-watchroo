package tokenwatchroo.providers

import cats.effect.IO
import tokenwatchroo.core.*

/** One monitored agent. `detect` probes the credentials, `fetch` never fails: errors become an unavailable snapshot,
  * except a rate limit after a good fetch, which returns that snapshot with the rate-limit text in `error` (issue
  * #51). A `Manual` trigger bypasses any cache the provider keeps. Card texts are in the language of `messages` (#10).
  */
trait UsageProvider {
  def id: AgentId
  def detect(config: Config): IO[Detection]
  def fetch(now: EpochSeconds, config: Config, trigger: FetchTrigger, messages: Messages): IO[AgentSnapshot]
}
