package tokenwatchroo.providers

import cats.effect.IO
import tokenwatchroo.core.*

/** One monitored agent. `detect` probes the credentials, `fetch` never fails: errors become an unavailable snapshot.
  * A `Manual` trigger bypasses any cache the provider keeps.
  */
trait UsageProvider {
  def id: AgentId
  def detect(config: Config): IO[Detection]
  def fetch(now: EpochSeconds, config: Config, trigger: FetchTrigger): IO[AgentSnapshot]
}
