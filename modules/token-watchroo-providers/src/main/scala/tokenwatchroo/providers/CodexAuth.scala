package tokenwatchroo.providers

import cats.effect.IO
import cats.syntax.all.*
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import tokenwatchroo.core.*
import tokenwatchroo.core.providers.{CodexAuthFile, CodexOAuth}

trait CodexAuthReader {
  def read(codexHome: CodexHome): IO[Either[ProviderError, CodexOAuth]]
}

/** `<codexHome>/auth.json`. The file is only ever read, never written. */
object CodexAuth extends CodexAuthReader {

  val FileName: String = "auth.json"

  override def read(codexHome: CodexHome): IO[Either[ProviderError, CodexOAuth]] =
    IO.blocking {
      val path = Paths.get(codexHome.value.value, FileName)
      if (Files.exists(path)) {
        CodexAuthFile
          .parse(Files.readString(path, StandardCharsets.UTF_8))
          .leftMap(ProviderError.credentialsMalformed)
      } else ProviderError.credentialsMissing.asLeft[CodexOAuth]
    }.handleError(e => ProviderError.credentialsMalformed(s"Cannot read $FileName: ${e.getMessage}").asLeft[CodexOAuth])
}
