package tokenwatchroo.providers

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import tokenwatchroo.core.*
import tokenwatchroo.core.providers.ClaudeOAuth

class SimulatedClaudeSignOutSpec extends munit.FunSuite {

  private val nowMillis = 1789185600000L

  private val keychainReader: ClaudeCredentialReader =
    new ClaudeCredentials(new Fakes.FakeKeychain(Fakes.claudeBlob.asRight))

  /** A flag file in a new temp directory, written only when `content` is present. */
  private def flag(content: Option[String]): os.Path = {
    val dir  = os.temp.dir(prefix = "tw-claude-signed-out")
    val file = dir / "flag"
    content.foreach(text => os.write(file, text))
    file
  }

  /** Counts the reads that reach the keychain reader. */
  final private class CountingReader(count: Ref[IO, Int]) extends ClaudeCredentialReader {
    override def read(attempt: ReadAttempt, nowMillis: Long): IO[Either[ProviderError, ClaudeOAuth]] =
      count.update(_ + 1) >> keychainReader.read(attempt, nowMillis)
  }

  test("a flag file holding signed-out reads as CredentialsMissing without touching the keychain") {
    val (result, reads) =
      (for {
        count  <- Ref.of[IO, Int](0)
        result <- new SimulatedClaudeSignOut(new CountingReader(count), flag("signed-out\n".some))
                    .read(ReadAttempt.First, nowMillis)
        reads  <- count.get
      } yield (result, reads)).unsafeRunSync()
    assertEquals(result, Left(ProviderError.CredentialsMissing))
    assertEquals(reads, 0)
  }

  test("any other content or a missing flag file passes the read through") {
    val (signedIn, missing, reads) =
      (for {
        count <- Ref.of[IO, Int](0)
        reader = new CountingReader(count)
        signedIn <- new SimulatedClaudeSignOut(reader, flag("signed-in".some)).read(ReadAttempt.First, nowMillis)
        missing  <- new SimulatedClaudeSignOut(reader, flag(none[String])).read(ReadAttempt.Subsequent, nowMillis)
        reads    <- count.get
      } yield (signedIn, missing, reads)).unsafeRunSync()
    assert(signedIn.isRight)
    assert(missing.isRight)
    assertEquals(reads, 2)
  }

  test("rewriting the flag between reads signs out and back in, and the provider detection follows") {
    val file       = flag("signed-in".some)
    val env        = Env.fromMap(Map(SimulatedClaudeSignOut.EnvVar -> file.toString))
    val detections =
      (for {
        provider  <- ClaudeCodeProvider.make(
                       new Fakes.FakeHttp(Fakes.ok(Fakes.claudeUsage)),
                       SimulatedClaudeSignOut.wrap(keychainReader, env),
                       IO.pure(none[ClaudeCodeVersion]),
                       IO.pure(nowMillis),
                     )
        signedIn  <- provider.detect(Fakes.config)
        _         <- IO.blocking(os.write.over(file, "signed-out"))
        signedOut <- provider.detect(Fakes.config)
        _         <- IO.blocking(os.write.over(file, "signed-in"))
        again     <- provider.detect(Fakes.config)
      } yield List(signedIn, signedOut, again)).unsafeRunSync()
    assertEquals(detections, List(Detection.Detected, Detection.NotDetected, Detection.Detected))
  }

  test("without the variable, or with a relative path, the reader is returned unchanged") {
    val relative = Env.fromMap(Map(SimulatedClaudeSignOut.EnvVar -> "relative/flag"))
    val absolute = Env.fromMap(Map(SimulatedClaudeSignOut.EnvVar -> flag(none[String]).toString))
    assert(SimulatedClaudeSignOut.wrap(keychainReader, Fakes.env) eq keychainReader)
    assert(SimulatedClaudeSignOut.wrap(keychainReader, relative) eq keychainReader)
    assert(SimulatedClaudeSignOut.wrap(keychainReader, absolute) match {
      case _: SimulatedClaudeSignOut => true
      case _ => false
    })
  }
}
