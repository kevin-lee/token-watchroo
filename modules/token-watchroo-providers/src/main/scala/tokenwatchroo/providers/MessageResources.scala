package tokenwatchroo.providers

import cats.syntax.all.*
import java.io.{ByteArrayOutputStream, InputStream}
import java.nio.charset.StandardCharsets
import tokenwatchroo.core.*

/** Loads the message bundles (#10) from the resources embedded in the binary (`nativeConfig.embedResources`, see
  * `build.sbt`). A missing or broken bundle is a build fault, so the library refuses to start with it.
  */
object MessageResources {

  def path(language: AppLanguage): String = s"/tokenwatchroo/i18n/messages_${language.tag}.properties"

  def load: Either[String, MessageBundles] =
    for {
      en      <- read(AppLanguage.En)
      ko      <- read(AppLanguage.Ko)
      bundles <- MessageBundles.from(en, ko)
    } yield bundles

  private def read(language: AppLanguage): Either[String, String] =
    Option(getClass.getResourceAsStream(path(language)))
      .toRight(s"missing ${path(language)}")
      .flatMap { stream =>
        Either
          .catchNonFatal {
            try new String(readAll(stream), StandardCharsets.UTF_8)
            finally stream.close()
          }
          .leftMap(e => s"cannot read ${path(language)}: ${Option(e.getMessage).getOrElse(e.getClass.getName)}")
      }

  private def readAll(stream: InputStream): Array[Byte] = {
    val out    = new ByteArrayOutputStream()
    val buffer = new Array[Byte](4096)
    Iterator
      .continually(stream.read(buffer))
      .takeWhile(_ =!= -1)
      .foreach(n => out.write(buffer, 0, n))
    out.toByteArray
  }
}
