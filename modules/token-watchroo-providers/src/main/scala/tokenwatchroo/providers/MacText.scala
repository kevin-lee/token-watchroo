package tokenwatchroo.providers

import cats.syntax.all.*
import java.nio.charset.StandardCharsets
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** Bindings to `src/main/resources/scala-native/mac_text.c` (#91). Scala Native 0.5.12 has no `java.text.Normalizer`
  * and no `java.security.MessageDigest`, so NFC comes from CoreFoundation and SHA-256 from CommonCrypto. Both C
  * functions are short and call nothing back, so they are not `@blocking`.
  */
@extern
object MacTextNative {

  /** The UTF-8 length of `in` in NFC, copied to `out` when it fits in `cap`, or -1. */
  @name("tw_nfc_utf8")
  def nfc(in: CString, out: Ptr[Byte], cap: CLong): CLong = extern

  /** The 32-byte SHA-256 digest of `len` bytes at `data`, written to `out32`. */
  @name("tw_sha256")
  def sha256(data: Ptr[Byte], len: CUnsignedLong, out32: Ptr[Byte]): Unit = extern
}

/** NFC and SHA-256 through [[MacTextNative]]. The bytes are copied into `Zone` memory, so C never gets a pointer into
  * the Scala heap.
  */
object MacText {

  val DigestLength: Int = 32

  /** `text` in Unicode Normalization Form C, or the reason when it cannot be converted. A NUL character is rejected,
    * because the C string would end there, and no folder text in an environment variable can hold one.
    */
  def nfc(text: String): Either[String, String] =
    if (text.contains('\u0000')) "contains a NUL character".asLeft[String]
    else
      Zone {
        val in     = toCString(text, StandardCharsets.UTF_8)
        val length = MacTextNative.nfc(in, null, 0L.toSize).toLong
        if (length < 0L) "not valid UTF-8".asLeft[String]
        else {
          val size = length.toInt
          val out  = alloc[Byte](math.max(size, 1))
          if (MacTextNative.nfc(in, out, length.toSize).toLong =!= length) "NFC conversion failed".asLeft[String]
          else new String(copied(out, size), StandardCharsets.UTF_8).asRight[String]
        }
      }

  /** The 32-byte SHA-256 digest of `bytes`. */
  def sha256(bytes: Array[Byte]): Array[Byte] =
    Zone {
      val data = alloc[Byte](math.max(bytes.length, 1))
      bytes.indices.foreach(i => data(i) = bytes(i))
      val out  = alloc[Byte](DigestLength)
      MacTextNative.sha256(data, bytes.length.toUSize, out)
      copied(out, DigestLength)
    }

  private def copied(from: Ptr[Byte], size: Int): Array[Byte] = {
    val bytes = new Array[Byte](size)
    bytes.indices.foreach(i => bytes(i) = from(i))
    bytes
  }

  /** Lower-case hex, two characters for each byte. */
  def hex(bytes: Array[Byte]): String =
    bytes.map(b => f"${b & 0xff}%02x").mkString
}
