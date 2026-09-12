package tokenwatchroo.providers

import cats.effect.IO
import cats.syntax.all.*
import java.nio.file.{Files, Paths}
import scala.concurrent.duration.*
import tokenwatchroo.core.*

/** Finds the installed Claude Code CLI and reads its version, used only for the `claude-code/<version>` User-Agent. */
object ClaudeCli {

  private val VersionTimeout: FiniteDuration = 3.seconds

  /** Candidate executables, in order. The last entry relies on PATH. */
  def candidates(env: Env): List[String] =
    env.home.toList.flatMap(home => List(s"$home/.claude/local/claude", s"$home/.local/bin/claude")) ++
      List("/opt/homebrew/bin/claude", "/usr/local/bin/claude", "claude")

  def detectVersion(env: Env): IO[Option[ClaudeCodeVersion]] =
    candidates(env).foldLeftM(none[ClaudeCodeVersion]) { (found, candidate) =>
      found match {
        case Some(_) => IO.pure(found)
        case None => versionOf(candidate)
      }
    }

  private def versionOf(executable: String): IO[Option[ClaudeCodeVersion]] = {
    val absent = executable.contains("/") && !Files.exists(Paths.get(executable))
    if (absent) IO.pure(none[ClaudeCodeVersion])
    else
      Processes
        .run(List(executable, "--version"), VersionTimeout)
        .map(_.toOption.filter(_.exitCode === 0).flatMap(output => parseVersion(output.stdout)))
  }

  /** The first whitespace-separated token that parses as a version: "2.1.269 (Claude Code)" gives 2.1.269. */
  def parseVersion(output: String): Option[ClaudeCodeVersion] =
    output.trim.split("\\s+").toList.collectFirst(Function.unlift(token => ClaudeCodeVersion.parse(token).toOption))
}
