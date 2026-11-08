package tokenwatchroo.core

import java.io.{ByteArrayOutputStream, InputStream}
import java.nio.charset.StandardCharsets

/** The message bundles of the core (#10), read from the resources embedded in the test binary. A bundle that is
  * missing or does not parse stops the test run, because every test that renders text needs it.
  */
object TestMessages {

  def text(language: AppLanguage): String = {
    val path   = s"/tokenwatchroo/i18n/messages_${language.tag}.properties"
    val stream = Option(getClass.getResourceAsStream(path)).getOrElse(sys.error(s"missing resource $path"))
    try new String(readAll(stream), StandardCharsets.UTF_8)
    finally stream.close()
  }

  lazy val bundles: MessageBundles =
    MessageBundles.from(text(AppLanguage.En), text(AppLanguage.Ko)).fold(sys.error, identity)

  lazy val en: Messages = bundles.en
  lazy val ko: Messages = bundles.ko

  private def readAll(stream: InputStream): Array[Byte] = {
    val out    = new ByteArrayOutputStream()
    val buffer = new Array[Byte](4096)
    Iterator.continually(stream.read(buffer)).takeWhile(_ != -1).foreach(n => out.write(buffer, 0, n))
    out.toByteArray
  }
}
