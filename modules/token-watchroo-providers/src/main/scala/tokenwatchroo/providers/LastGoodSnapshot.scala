package tokenwatchroo.providers

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*
import tokenwatchroo.core.*

/** The last snapshot a provider read successfully, keyed by the access token it was read with. On `RateLimited` the
  * provider returns it with the rate-limit text in `error`, so the card keeps its meters (issue #51). A different
  * token is another account, so the entry never applies to it, and a token rotation by the CLI loses the carry once.
  */
final case class LastGoodSnapshot(token: AccessToken, snapshot: AgentSnapshot) derives CanEqual, Eq, Show

object LastGoodSnapshot {
  extension (last: LastGoodSnapshot) {
    def carriedFor(token: AccessToken, note: ErrorMessage): Option[AgentSnapshot] =
      Option.when(last.token === token)(last.snapshot.copy(error = note.some))
  }
}
