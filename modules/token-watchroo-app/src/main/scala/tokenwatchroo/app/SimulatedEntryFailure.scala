package tokenwatchroo.app

import cats.syntax.all.*
import tokenwatchroo.providers.Env

/** Debug only, for testing `Entry.report` without a real failure. While the file named by `TW_DEBUG_ENTRY_FAILURE_FILE`
  * holds `fail` (trimmed), `tw_refresh` throws inside the managed region, so `Entry.report` writes the failure to stderr
  * and the call returns `Entry.Internal`. Any other content, a missing file, or an unreadable file does nothing. The
  * file is read on every refresh.
  */
object SimulatedEntryFailure {

  val EnvVar: String = "TW_DEBUG_ENTRY_FAILURE_FILE"

  val Fail: String = "fail"

  final class Failure extends RuntimeException("simulated entry point failure (TW_DEBUG_ENTRY_FAILURE_FILE)")

  /** The flag file, only when the variable holds an absolute path. */
  def flagFile(env: Env): Option[os.Path] =
    env.get(EnvVar).flatMap(value => Either.catchNonFatal(os.Path(value)).toOption)

  def shouldFail(flagFile: os.Path): Boolean =
    Either.catchNonFatal(os.exists(flagFile) && os.read(flagFile).trim === Fail).getOrElse(false)

  def check(flagFile: Option[os.Path]): Unit =
    if (flagFile.exists(shouldFail)) throw new Failure else ()
}
