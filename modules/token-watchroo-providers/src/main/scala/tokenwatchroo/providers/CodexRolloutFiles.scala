package tokenwatchroo.providers

import cats.effect.IO
import cats.syntax.all.*
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import tokenwatchroo.core.*
import tokenwatchroo.core.providers.{CodexRollout, CodexRolloutEvent}

/** The newest rollout log. Codex compresses a log after 7 days without a change (`MIN_ROLLOUT_AGE` in codex-rs
  * `rollout/src/compression.rs`), so a `Compressed` log has no window that has not reset and is never decoded (issue
  * #81).
  */
enum RolloutLog derives CanEqual {
  case Plain(path: Path)
  case Compressed(path: Path)
}

trait RolloutFiles {
  def newest(codexHome: CodexHome): IO[Option[RolloutLog]]
  def latestEvent(path: Path): IO[Option[CodexRolloutEvent]]
}

/** The rollout logs under `<codexHome>/sessions`, the network-free fallback for Codex. */
object CodexRolloutFiles extends RolloutFiles {

  private val MaxDepth = 4

  private val PlainSuffix      = ".jsonl"
  private val CompressedSuffix = ".jsonl.zst"

  /** The most recently modified `*.jsonl` or `*.jsonl.zst` log, marked as compressed or plain. */
  override def newest(codexHome: CodexHome): IO[Option[RolloutLog]] =
    IO.blocking {
      val sessions = Paths.get(codexHome.value.value, "sessions")
      if (Files.isDirectory(sessions)) {
        val stream = Files.walk(sessions, MaxDepth)
        try {
          stream
            .iterator()
            .asScala
            .filter { p =>
              val name = p.getFileName.toString
              Files.isRegularFile(p) && (name.endsWith(PlainSuffix) || name.endsWith(CompressedSuffix))
            }
            .toList
            .maxByOption(p => Files.getLastModifiedTime(p).toMillis)
            .map(p =>
              if (p.getFileName.toString.endsWith(CompressedSuffix)) RolloutLog.Compressed(p) else RolloutLog.Plain(p)
            )
        } finally stream.close()
      } else none[RolloutLog]
    }.handleError(_ => none[RolloutLog])

  /** Streams the log line by line, so memory stays bounded whatever its size (#65). A file that cannot be read or
    * decoded gives none, as a missing one does.
    */
  override def latestEvent(path: Path): IO[Option[CodexRolloutEvent]] =
    IO.blocking(
      os.read
        .lines
        .stream(os.Path(path.toAbsolutePath))
        .foldLeft(none[CodexRolloutEvent])(CodexRollout.withLine)
    ).handleError(_ => none[CodexRolloutEvent])
}
