package tokenwatchroo.core

import cats.syntax.all.*

/** The message bundles of every language (#10), loaded once when the library starts. */
final case class MessageBundles(en: Messages, ko: Messages) derives CanEqual

object MessageBundles {

  extension (bundles: MessageBundles) {
    def of(language: AppLanguage): Messages = language match {
      case AppLanguage.En => bundles.en
      case AppLanguage.Ko => bundles.ko
    }
  }

  /** Both bundles from their text. Fails with the problems of each bundle that does not parse. */
  def from(enText: String, koText: String): Either[String, MessageBundles] =
    (
      Messages.parse(AppLanguage.En, enText).toValidatedNel,
      Messages.parse(AppLanguage.Ko, koText).toValidatedNel,
    ).mapN(MessageBundles.apply).toEither.leftMap(_.toList.mkString(" | "))
}
