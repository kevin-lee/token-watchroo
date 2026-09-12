package tokenwatchroo.providers

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*
import tokenwatchroo.core.*

/** The last profile lookup for one access token. `label` is the last decoded profile result (which may be none) or,
  * after a failure, the label carried over from the previous entry for the same token. `fetchedAt` is the time of the
  * last attempt, success or failure, so scheduled ticks wait a full TTL between attempts. A different token means
  * another account, so the entry never applies to it.
  */
final case class ProfileCacheEntry(token: AccessToken, label: Option[PlanLabel], fetchedAt: EpochSeconds)
    derives CanEqual,
      Eq,
      Show

object ProfileCacheEntry {
  extension (entry: ProfileCacheEntry) {
    def isFresh(token: AccessToken, now: EpochSeconds, ttl: Seconds, trigger: FetchTrigger): Boolean =
      entry.token === token && (trigger match {
        case FetchTrigger.Scheduled => entry.fetchedAt.plus(ttl) > now
        case FetchTrigger.Manual => false
      })
  }
}
