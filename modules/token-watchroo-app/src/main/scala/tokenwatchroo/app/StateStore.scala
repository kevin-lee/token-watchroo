package tokenwatchroo.app

import cats.effect.{IO, Ref}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import tokenwatchroo.core.*
import tokenwatchroo.core.codecs.given

trait StateStore {
  def read: IO[AlertState]
  def write(state: AlertState): IO[Unit]
}

/** `<stateDir>/state.json`, written to a temp file and renamed. A missing or corrupt file is an empty state. */
final class FileStateStore(stateDir: StateDir) extends StateStore {

  private val directory: Path = Paths.get(stateDir.value.value)
  private val file: Path      = directory.resolve(FileStateStore.FileName)
  private val temp: Path      = directory.resolve(FileStateStore.FileName + ".tmp")

  override def read: IO[AlertState] =
    IO.blocking {
      if (Files.exists(file)) {
        codecs.readEither[AlertState](Files.readString(file, StandardCharsets.UTF_8)).getOrElse(AlertState.empty)
      } else AlertState.empty
    }.handleError(_ => AlertState.empty)

  override def write(state: AlertState): IO[Unit] =
    IO.blocking {
      val _ = Files.createDirectories(directory)
      val _ = Files.write(temp, codecs.write(state).getBytes(StandardCharsets.UTF_8))
      val _ = Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
    }
}

object FileStateStore {
  val FileName: String = "state.json"
}

/** For tests. */
final class InMemoryStateStore(ref: Ref[IO, AlertState]) extends StateStore {
  override def read: IO[AlertState]               = ref.get
  override def write(state: AlertState): IO[Unit] = ref.set(state)
}

object InMemoryStateStore {
  def make: IO[InMemoryStateStore] = Ref.of[IO, AlertState](AlertState.empty).map(new InMemoryStateStore(_))
}
