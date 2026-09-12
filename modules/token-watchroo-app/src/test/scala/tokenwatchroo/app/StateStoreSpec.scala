package tokenwatchroo.app

import cats.effect.unsafe.implicits.global
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import refined4s.types.all.*
import tokenwatchroo.core.*

class StateStoreSpec extends munit.FunSuite {

  private def store(): (FileStateStore, java.nio.file.Path) = {
    val dir = Files.createTempDirectory("tw-state")
    (new FileStateStore(StateDir(NonEmptyString.unsafeFrom(dir.toString))), dir)
  }

  test("a missing file reads as the empty state") {
    val (s, _) = store()
    assertEquals(s.read.unsafeRunSync(), AlertState.empty)
  }

  test("write then read round-trips and leaves no temp file behind") {
    val (s, dir) = store()
    val state    = AlertState(
      Map(
        WindowKey(AgentId.Codex, WindowId.Session) ->
          WindowRecord(EpochSeconds(1789187040L), UsedPercent.clamp(82.0d), Set(AlertKind.Warning80))
      )
    )
    (s.write(state) *> s.write(state)).unsafeRunSync()
    assertEquals(s.read.unsafeRunSync(), state)
    assert(Files.exists(dir.resolve(FileStateStore.FileName)))
    assert(!Files.exists(dir.resolve(FileStateStore.FileName + ".tmp")))
  }

  test("a corrupt file reads as the empty state") {
    val (s, dir) = store()
    val _        = Files.write(dir.resolve(FileStateStore.FileName), "{not json".getBytes(StandardCharsets.UTF_8))
    assertEquals(s.read.unsafeRunSync(), AlertState.empty)
  }

  test("the state directory is created on first write") {
    val dir = Files.createTempDirectory("tw-state-parent").resolve("nested").resolve("deeper")
    val s   = new FileStateStore(StateDir(NonEmptyString.unsafeFrom(dir.toString)))
    s.write(AlertState.empty).unsafeRunSync()
    assert(Files.exists(dir.resolve(FileStateStore.FileName)))
  }
}
