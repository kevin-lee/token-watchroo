package tokenwatchroo.providers

import scala.scalanative.unsafe.*

/** Bindings to the C feeder in `src/test/resources/scala-native/curl_buffer_spec_feeder.c`. It drives the real
  * libcurl write callback without libcurl, from C so that no Scala memory crosses while the thread is Unmanaged.
  * `feed` is `@blocking`, so the calling thread is Unmanaged for the whole run, as it is inside `curl_easy_perform`.
  */
@extern
object CurlBufferSpecFeeder {

  /** Feeds `total` pattern bytes in uneven chunks of at most `maxChunk`, pausing `pauseNanos` after each accepted
    * chunk, and returns how many bytes the callback accepted.
    */
  @blocking
  @name("tw_test_curl_feed")
  def feed(buffer: Ptr[Byte], total: CSize, maxChunk: CSize, pauseNanos: CSize): CSize = extern

  /** How many calls `feed` makes for these arguments when every chunk is accepted. */
  @name("tw_test_curl_chunks")
  def chunks(total: CSize, maxChunk: CSize): CSize = extern
}
