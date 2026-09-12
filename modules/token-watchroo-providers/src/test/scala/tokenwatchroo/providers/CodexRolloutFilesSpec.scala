package tokenwatchroo.providers

import cats.effect.unsafe.implicits.global
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.nio.file.attribute.FileTime
import refined4s.types.all.*
import tokenwatchroo.core.*

class CodexRolloutFilesSpec extends munit.FunSuite {

  private def write(path: Path, content: String, modifiedAtMillis: Long): Unit = {
    val _ = Files.createDirectories(path.getParent)
    val _ = Files.write(path, content.getBytes(StandardCharsets.UTF_8))
    val _ = Files.setLastModifiedTime(path, FileTime.fromMillis(modifiedAtMillis))
  }

  test("the most recently modified jsonl under sessions wins") {
    val dir    = Files.createTempDirectory("tw-rollouts")
    write(dir.resolve("sessions/2026/09/11/older.jsonl"), "old\n", 1_700_000_000_000L)
    write(dir.resolve("sessions/2026/09/12/newer.jsonl"), "new\n", 1_700_000_100_000L)
    write(dir.resolve("sessions/2026/09/12/notes.txt"), "ignored\n", 1_700_000_200_000L)
    val home   = CodexHome(NonEmptyString.unsafeFrom(dir.toString))
    val newest = CodexRolloutFiles.newest(home).unsafeRunSync()
    assertEquals(newest.map(_.getFileName.toString), Some("newer.jsonl"))
    assertEquals(newest.map(p => CodexRolloutFiles.readLines(p).unsafeRunSync()), Some(List("new")))
  }

  test("a home without sessions gives none") {
    val dir = Files.createTempDirectory("tw-rollouts-empty")
    assertEquals(CodexRolloutFiles.newest(CodexHome(NonEmptyString.unsafeFrom(dir.toString))).unsafeRunSync(), None)
  }
}
