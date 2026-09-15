package tokenwatchroo.providers

import cats.effect.IO
import cats.syntax.all.*
import java.nio.charset.StandardCharsets
import scala.concurrent.duration.FiniteDuration
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import tokenwatchroo.core.*

/** The libcurl `HttpClient`. Each request is one blocking `curl_easy_perform` inside `IO.blocking`. The timeout is
  * per request and clamped to at least one second, because libcurl reads 0 as no timeout.
  *
  * Response bytes are collected by the C write callback in `src/main/resources/scala-native/curl_write_buffer.c`
  * into a malloc-backed buffer owned by C, reached through [[CurlBuffer]]. The callback is C because
  * `curl_easy_perform` is `@blocking`, so the thread is in the unmanaged GC state while it runs, and a Scala
  * `CFuncPtr` allocates boxes in its forwarder and `Tag` objects in its body while the optimiser is off (#41). The
  * buffer becomes a Scala string only after `curl_easy_perform` returns.
  */
object CurlHttp extends HttpClient {

  private val ConnectTimeoutSeconds = 10L

  /** Must run once before the first request. */
  val globalInit: IO[Unit] = IO.blocking {
    val _ = LibCurl.curl_global_init(CurlGlobal.Default)
  }

  override def get(
    url: String,
    headers: List[(String, String)],
    userAgent: UserAgent,
    timeout: FiniteDuration,
  ): IO[Either[ProviderError, HttpResponse]] =
    IO.blocking(perform(url, headers, userAgent, timeout))

  private def perform(
    url: String,
    headers: List[(String, String)],
    userAgent: UserAgent,
    timeout: FiniteDuration,
  ): Either[ProviderError, HttpResponse] =
    Zone {
      val totalSeconds = math.max(1L, timeout.toSeconds)
      val curl         = LibCurl.curl_easy_init()
      if (curl == null) ProviderError.network("curl_easy_init failed").asLeft[HttpResponse]
      else {
        val buffer     = CurlBuffer.create()
        val headerList = headers.foldLeft(null.asInstanceOf[LibCurl.SList]) {
          case (list, (name, value)) =>
            LibCurl.curl_slist_append(list, toCString(s"$name: $value"))
        }
        try {
          if (buffer == null) ProviderError.network("out of memory").asLeft[HttpResponse]
          else {
            val _    = LibCurl.curl_easy_setopt(curl, CurlOpt.Url, toCString(url))
            val _    = LibCurl.curl_easy_setopt(curl, CurlOpt.UserAgent, toCString(userAgent.value.value))
            val _    = LibCurl.curl_easy_setopt(curl, CurlOpt.HttpHeader, headerList)
            val _    = LibCurl.curl_easy_setopt(curl, CurlOpt.WriteFunction, CurlBuffer.callback())
            val _    = LibCurl.curl_easy_setopt(curl, CurlOpt.WriteData, buffer)
            val _    = LibCurl.curl_easy_setopt(curl, CurlOpt.Timeout, totalSeconds)
            val _    =
              LibCurl.curl_easy_setopt(curl, CurlOpt.ConnectTimeout, math.min(ConnectTimeoutSeconds, totalSeconds))
            val _    = LibCurl.curl_easy_setopt(curl, CurlOpt.NoSignal, 1L)
            val _    = LibCurl.curl_easy_setopt(curl, CurlOpt.FollowLocation, 0L)
            val code = LibCurl.curl_easy_perform(curl)
            if (code =!= 0) {
              if (CurlBuffer.failed(buffer) =!= 0) ProviderError.network("out of memory").asLeft[HttpResponse]
              else
                ProviderError
                  .network(s"curl $code: ${fromCString(LibCurl.curl_easy_strerror(code))}")
                  .asLeft[HttpResponse]
            } else {
              val statusPtr: Ptr[CLong] = alloc[CLong]()
              val status                =
                if (LibCurl.curl_easy_getinfo(curl, CurlInfo.ResponseCode, statusPtr) === 0) (!statusPtr).toInt else 0
              val length                = CurlBuffer.length(buffer).toInt
              val bytes                 = new Array[Byte](length)
              if (length > 0) {
                val _ = CurlBuffer.copy(buffer, bytes.at(0), length.toCSize)
              } else ()
              classify(HttpStatus(status), new String(bytes, StandardCharsets.UTF_8))
            }
          }
        } finally {
          CurlBuffer.free(buffer)
          if (headerList != null) LibCurl.curl_slist_free_all(headerList) else ()
          LibCurl.curl_easy_cleanup(curl)
        }
      }
    }

  private def classify(status: HttpStatus, body: String): Either[ProviderError, HttpResponse] =
    status.value match {
      case 401 | 403 => ProviderError.tokenExpired(status).asLeft[HttpResponse]
      case 429 => ProviderError.rateLimited.asLeft[HttpResponse]
      case _ => Either.cond(status.isSuccess, HttpResponse(status, body), ProviderError.http(status))
    }
}
