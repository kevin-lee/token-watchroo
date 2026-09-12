package tokenwatchroo.providers

import cats.{Eq, Show}
import cats.derived.*
import cats.effect.IO
import cats.syntax.all.*
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

final case class ProcessOutput(exitCode: Int, stdout: String) derives CanEqual, Eq, Show

/** Runs a short-lived child process with a hard timeout. No working directory is set, so Scala Native spawns it with
  * `posix_spawn` rather than `fork`, which is what a multithreaded GUI process needs.
  */
object Processes {

  def run(command: List[String], timeout: FiniteDuration): IO[Either[ProviderError, ProcessOutput]] =
    IO.blocking {
      val builder = new ProcessBuilder(command*).redirectErrorStream(true)
      val process = builder.start()
      val done    = process.waitFor(timeout.toMillis, TimeUnit.MILLISECONDS)
      if (done) {
        val stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
        ProcessOutput(process.exitValue(), stdout).asRight[ProviderError]
      } else {
        val _ = process.destroyForcibly()
        val _ = process.waitFor()
        ProviderError.timeout.asLeft[ProcessOutput]
      }
    }.handleError { e =>
      ProviderError
        .credentialsMalformed(s"Could not run ${command.headOption.getOrElse("process")}: ${e.getMessage}")
        .asLeft[ProcessOutput]
    }
}
