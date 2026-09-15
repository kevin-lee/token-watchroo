package tokenwatchroo.providers

import scala.scalanative.unsafe.*

/** Bindings to the C libcurl write callback and its buffer in `src/main/resources/scala-native/curl_write_buffer.c`.
  *
  * The callback is C because libcurl calls it inside `curl_easy_perform`, which is `@blocking`, so the thread is
  * Unmanaged, and a Scala function passed as a `CFuncPtr4` boxes its `Ptr` and `CSize` arguments on the Scala heap in
  * the generated forwarder while the optimiser is off (#41). The buffer is malloc memory owned by C. It is read only
  * after `curl_easy_perform` returns, on the same thread.
  */
@extern
object CurlBuffer {

  /** A new empty buffer, or null when malloc fails. */
  @name("tw_curl_buffer_new")
  def create(): Ptr[Byte] = extern

  /** Releases the buffer. Null tolerant. */
  @name("tw_curl_buffer_free")
  def free(buffer: Ptr[Byte]): Unit = extern

  /** The C function to pass as `CURLOPT_WRITEFUNCTION`, with the buffer as `CURLOPT_WRITEDATA`. */
  @name("tw_curl_buffer_callback")
  def callback(): CFuncPtr4[Ptr[Byte], CSize, CSize, Ptr[Byte], CSize] = extern

  /** Bytes received so far. */
  @name("tw_curl_buffer_length")
  def length(buffer: Ptr[Byte]): CSize = extern

  /** 1 when growing the buffer failed and the transfer was aborted, otherwise 0. */
  @name("tw_curl_buffer_failed")
  def failed(buffer: Ptr[Byte]): CInt = extern

  /** Copies up to `capacity` received bytes into `dest` and returns how many were copied. */
  @name("tw_curl_buffer_copy")
  def copy(buffer: Ptr[Byte], dest: Ptr[Byte], capacity: CSize): CSize = extern
}
