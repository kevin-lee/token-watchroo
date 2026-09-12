package tokenwatchroo.providers

import scala.scalanative.unsafe.*

/** Minimal bindings to libcurl's easy (synchronous) API, in the style of claude-proxymate's `LibCurl.scala`.
  *
  * `curl_easy_perform` is `@blocking`: the calling thread leaves the managed GC state for the duration of the call, so
  * a slow request never stalls a collection. Consequently the write callback registered for it runs unmanaged and
  * must not touch Scala memory (see `CurlHttp`).
  */
@link("curl")
@extern
object LibCurl {
  type Curl     = Ptr[Byte]
  type CurlCode = CInt
  type SList    = Ptr[Byte]

  def curl_global_init(flags: CLong): CurlCode                         = extern
  def curl_easy_init(): Curl                                           = extern
  def curl_easy_cleanup(curl: Curl): Unit                              = extern
  @blocking def curl_easy_perform(curl: Curl): CurlCode                = extern
  def curl_easy_setopt(curl: Curl, option: CInt, args: Any*): CurlCode = extern
  def curl_easy_getinfo(curl: Curl, info: CInt, args: Any*): CurlCode  = extern
  def curl_easy_strerror(code: CurlCode): CString                      = extern
  def curl_slist_append(list: SList, str: CString): SList              = extern
  def curl_slist_free_all(list: SList): Unit                           = extern
}

object CurlGlobal {
  val Default: CLong = 3L.toSize // CURL_GLOBAL_DEFAULT
}

/** CURLOPTTYPE_LONG = 0, OBJECTPOINT = 10000, FUNCTIONPOINT = 20000 */
object CurlOpt {
  val WriteData: CInt      = 10001 // CURLOPT_WRITEDATA
  val Url: CInt            = 10002 // CURLOPT_URL
  val UserAgent: CInt      = 10018 // CURLOPT_USERAGENT
  val HttpHeader: CInt     = 10023 // CURLOPT_HTTPHEADER
  val WriteFunction: CInt  = 20011 // CURLOPT_WRITEFUNCTION
  val Timeout: CInt        = 13 // CURLOPT_TIMEOUT (seconds)
  val FollowLocation: CInt = 52 // CURLOPT_FOLLOWLOCATION
  val ConnectTimeout: CInt = 78 // CURLOPT_CONNECTTIMEOUT (seconds)
  val NoSignal: CInt       = 99 // CURLOPT_NOSIGNAL
}

object CurlInfo {
  val ResponseCode: CInt = 0x200002 // CURLINFO_RESPONSE_CODE
}
