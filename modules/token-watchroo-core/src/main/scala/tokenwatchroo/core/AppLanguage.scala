package tokenwatchroo.core

import cats.{Eq, Show}
import cats.derived.*
import cats.syntax.all.*

/** The language of the text that the core makes (#10): notification copy and card texts. The shell chooses it at
  * launch from macOS (the preferred languages or the per-app language) and sends it as `locale` in the config JSON. It
  * does not change during a run.
  */
enum AppLanguage derives CanEqual, Eq, Show {
  case En
  case Ko
}

object AppLanguage {
  def en: AppLanguage = AppLanguage.En
  def ko: AppLanguage = AppLanguage.Ko

  val all: List[AppLanguage] = AppLanguage.values.toList

  extension (language: AppLanguage) {
    def wire: String = language match {
      case AppLanguage.En => "en"
      case AppLanguage.Ko => "ko"
    }

    /** The tag in the bundle file name, `messages_<tag>.properties`. */
    def tag: String = language.wire
  }

  /** `en`, `ko`, or a BCP 47 tag that starts with `en-` or `ko-`, in any letter case. Scala Native's javalib has no
    * `java.util.Locale`, so the text is lower-cased for ASCII letters only.
    */
  def parse(s: String): Either[String, AppLanguage] = {
    val lower = asciiLowerCase(s)
    all
      .find(language => lower === language.wire || lower.startsWith(s"${language.wire}-"))
      .toRight(s"Unknown AppLanguage: $s")
  }

  /** A missing or unknown value is English. */
  def fromWire(wire: Option[String]): AppLanguage =
    wire.flatMap(parse(_).toOption).getOrElse(AppLanguage.En)

  private def asciiLowerCase(s: String): String =
    s.map(c => if (c >= 'A' && c <= 'Z') (c + ('a' - 'A')).toChar else c)
}
