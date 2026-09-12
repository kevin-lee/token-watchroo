package tokenwatchroo.providers

import cats.effect.IO
import cats.syntax.all.*
import java.nio.charset.StandardCharsets
import scala.scalanative.libc.{stdlib, string}
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import tokenwatchroo.core.*

/** The libcurl `HttpClient`. Each request is one blocking `curl_easy_perform` inside `IO.blocking`.
  *
  * Response bytes are collected by a C callback into a malloc-backed buffer described by a `CStruct3` of data pointer,
  * length, and capacity. Invariant: the callback allocates no Scala objects and touches no Scala references, because
  * `curl_easy_perform` is `@blocking` and the thread is in the unmanaged GC state while it runs. The buffer is turned
  * into a Scala string only after `curl_easy_perform` returns.
  */
object CurlHttp extends HttpClient {

  private type Buffer = CStruct3[Ptr[Byte], CSize, CSize]

  private val InitialCapacity: CSize = 8192.toCSize
  private val TotalTimeoutSeconds    = 20L
  private val ConnectTimeoutSeconds  = 10L

  /** Must run once before the first request. */
  val globalInit: IO[Unit] = IO.blocking {
    val _ = LibCurl.curl_global_init(CurlGlobal.Default)
  }

  private val writeCallback: CFuncPtr4[Ptr[Byte], CSize, CSize, Ptr[Byte], CSize] =
    CFuncPtr4.fromScalaFunction { (data: Ptr[Byte], size: CSize, count: CSize, userdata: Ptr[Byte]) =>
      val buffer   = userdata.asInstanceOf[Ptr[Buffer]]
      val incoming = size * count
      val needed   = buffer._2 + incoming
      val grown    =
        if (needed > buffer._3) {
          val capacity = if (needed > buffer._3 * 2.toCSize) needed else buffer._3 * 2.toCSize
          val moved    = stdlib.realloc(buffer._1, capacity)
          if (moved == null) false
          else {
            buffer._1 = moved
            buffer._3 = capacity
            true
          }
        } else true
      if (grown) {
        val _ = string.memcpy(buffer._1 + buffer._2, data, incoming)
        buffer._2 = needed
        incoming
      } else 0.toCSize // tells libcurl to abort the transfer
    }

  override def get(
    url: String,
    headers: List[(String, String)],
    userAgent: UserAgent,
  ): IO[Either[ProviderError, HttpResponse]] =
    IO.blocking(perform(url, headers, userAgent))

  private def perform(
    url: String,
    headers: List[(String, String)],
    userAgent: UserAgent
  ): Either[ProviderError, HttpResponse] =
    Zone {
      val curl = LibCurl.curl_easy_init()
      if (curl == null) ProviderError.network("curl_easy_init failed").asLeft[HttpResponse]
      else {
        val buffer     = alloc[Buffer]()
        buffer._1 = stdlib.malloc(InitialCapacity)
        buffer._2 = 0.toCSize
        buffer._3 = InitialCapacity
        val headerList = headers.foldLeft(null.asInstanceOf[LibCurl.SList]) {
          case (list, (name, value)) =>
            LibCurl.curl_slist_append(list, toCString(s"$name: $value"))
        }
        try {
          if (buffer._1 == null) ProviderError.network("out of memory").asLeft[HttpResponse]
          else {
            val _    = LibCurl.curl_easy_setopt(curl, CurlOpt.Url, toCString(url))
            val _    = LibCurl.curl_easy_setopt(curl, CurlOpt.UserAgent, toCString(userAgent.value.value))
            val _    = LibCurl.curl_easy_setopt(curl, CurlOpt.HttpHeader, headerList)
            val _    = LibCurl.curl_easy_setopt(curl, CurlOpt.WriteFunction, writeCallback)
            val _    = LibCurl.curl_easy_setopt(curl, CurlOpt.WriteData, buffer.asInstanceOf[Ptr[Byte]])
            val _    = LibCurl.curl_easy_setopt(curl, CurlOpt.Timeout, TotalTimeoutSeconds)
            val _    = LibCurl.curl_easy_setopt(curl, CurlOpt.ConnectTimeout, ConnectTimeoutSeconds)
            val _    = LibCurl.curl_easy_setopt(curl, CurlOpt.NoSignal, 1L)
            val _    = LibCurl.curl_easy_setopt(curl, CurlOpt.FollowLocation, 0L)
            val code = LibCurl.curl_easy_perform(curl)
            if (code =!= 0) {
              ProviderError
                .network(s"curl $code: ${fromCString(LibCurl.curl_easy_strerror(code))}")
                .asLeft[HttpResponse]
            } else {
              val statusPtr: Ptr[CLong] = alloc[CLong]()
              val status                =
                if (LibCurl.curl_easy_getinfo(curl, CurlInfo.ResponseCode, statusPtr) === 0) (!statusPtr).toInt else 0
              val length                = buffer._2.toInt
              val bytes                 = new Array[Byte](length)
              if (length > 0) {
                val _ = string.memcpy(bytes.at(0), buffer._1, buffer._2)
              } else ()
              classify(HttpStatus(status), new String(bytes, StandardCharsets.UTF_8))
            }
          }
        } finally {
          if (buffer._1 != null) stdlib.free(buffer._1) else ()
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
