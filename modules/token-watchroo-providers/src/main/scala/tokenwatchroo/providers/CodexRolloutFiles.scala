package tokenwatchroo.providers

import cats.effect.IO
import cats.syntax.all.*
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import tokenwatchroo.core.*
import tokenwatchroo.core.providers.{CodexRollout, CodexRolloutRateLimits}

trait RolloutFiles {
  def newest(codexHome: CodexHome): IO[Option[Path]]
  def latestRateLimits(path: Path): IO[Option[CodexRolloutRateLimits]]
}

/** The rollout logs under `<codexHome>/sessions`, the network-free fallback for Codex. */
object CodexRolloutFiles extends RolloutFiles {

  private val MaxDepth = 4

  override def newest(codexHome: CodexHome): IO[Option[Path]] =
    IO.blocking {
      val sessions = Paths.get(codexHome.value.value, "sessions")
      if (Files.isDirectory(sessions)) {
        val stream = Files.walk(sessions, MaxDepth)
        try {
          stream
            .iterator()
            .asScala
            .filter(p => Files.isRegularFile(p) && p.getFileName.toString.endsWith(".jsonl"))
            .toList
            .maxByOption(p => Files.getLastModifiedTime(p).toMillis)
        } finally stream.close()
      } else none[Path]
    }.handleError(_ => none[Path])

  /** Streams the log line by line, so memory stays bounded whatever its size (#65). A file that cannot be read or
    * decoded gives none, as a missing one does.
    */
  override def latestRateLimits(path: Path): IO[Option[CodexRolloutRateLimits]] =
    IO.blocking(
      os.read
        .lines
        .stream(os.Path(path.toAbsolutePath))
        .foldLeft(none[CodexRolloutRateLimits])(CodexRollout.withLine)
    ).handleError(_ => none[CodexRolloutRateLimits])
}
