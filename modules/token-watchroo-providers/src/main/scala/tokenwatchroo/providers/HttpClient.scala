package tokenwatchroo.providers

import cats.{Eq, Show}
import cats.derived.*
import cats.effect.IO
import refined4s.*
import refined4s.modules.cats.derivation.*
import scala.concurrent.duration.*
import tokenwatchroo.core.*

type HttpStatus = HttpStatus.Type
object HttpStatus extends Newtype[Int], CatsEqShow[Int], CatsOrder[Int] {
  extension (status: HttpStatus) {
    def isSuccess: Boolean = status.value >= 200 && status.value <= 299
  }
}

final case class HttpResponse(status: HttpStatus, body: String) derives CanEqual, Eq, Show

/** The one HTTP operation the providers need. Kept as a trait so the providers are testable offline with a fake.
  * `timeout` bounds the whole request, so a cosmetic call can use a shorter budget than a usage call.
  */
trait HttpClient {
  def get(
    url: String,
    headers: List[(String, String)],
    userAgent: UserAgent,
    timeout: FiniteDuration,
  ): IO[Either[ProviderError, HttpResponse]]
}

object HttpClient {

  /** The usage endpoints keep the v1 budget. */
  val StandardTimeout: FiniteDuration = 20.seconds
}
