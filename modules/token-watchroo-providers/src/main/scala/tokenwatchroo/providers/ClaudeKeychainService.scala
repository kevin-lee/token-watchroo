package tokenwatchroo.providers

import cats.syntax.all.*
import java.nio.charset.StandardCharsets
import tokenwatchroo.core.*

/** The keychain service of the Claude Code sign-in for a custom config folder (#91).
  *
  * Claude Code (2.1.319, `BV()` in its bundle) names the item `Claude Code-credentials-` plus the first 8 hex characters
  * of sha256 of the folder text in NFC. The text is `CLAUDE_SECURESTORAGE_CONFIG_DIR` when it is set and not empty,
  * else `CLAUDE_CONFIG_DIR`. Claude Code hashes the raw text: it does not resolve symbolic links, expand `~` or remove a
  * trailing slash. A real item made with `claude auth login` proved this on 2026-11-08: only the exact text found it.
  * The item has the same account as the plain item (`KeychainAccount.claudeCode`, #90).
  */
object ClaudeKeychainService {

  val SuffixLength: Int = 8

  /** The first 8 hex characters of sha256 of `text` in NFC. */
  def suffix(text: String): Either[String, String] =
    MacText
      .nfc(text)
      .map(normalized => MacText.hex(MacText.sha256(normalized.getBytes(StandardCharsets.UTF_8))).take(SuffixLength))

  def forConfigDir(dir: ClaudeConfigDir): Either[String, String] =
    suffix(dir.value).map(hash => s"${ClaudeCredentials.Service}-$hash")

  /** The folder with one trailing `/` removed, or with one added. `/` has none. */
  def slashToggled(dir: ClaudeConfigDir): Option[ClaudeConfigDir] =
    if (dir.value === "/") none[ClaudeConfigDir]
    else if (dir.value.endsWith("/")) ClaudeConfigDir.from(dir.value.dropRight(1)).toOption
    else ClaudeConfigDir.from(s"${dir.value}/").toOption

  /** The services to read in order: the folder as written, then with the trailing slash toggled, because a trailing
    * slash is the difference that users miss most easily.
    */
  def candidates(dir: ClaudeConfigDir): Either[String, List[String]] =
    (dir :: slashToggled(dir).toList).traverse(forConfigDir)
}
