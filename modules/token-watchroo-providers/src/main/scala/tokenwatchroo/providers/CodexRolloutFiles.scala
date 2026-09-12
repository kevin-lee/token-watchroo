package tokenwatchroo.providers

import cats.effect.IO
import cats.syntax.all.*
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import tokenwatchroo.core.*

trait RolloutFiles {
  def newest(codexHome: CodexHome): IO[Option[Path]]
  def readLines(path: Path): IO[List[String]]
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

  override def readLines(path: Path): IO[List[String]] =
    IO.blocking(Files.readAllLines(path, StandardCharsets.UTF_8).asScala.toList).handleError(_ => Nil)
}
