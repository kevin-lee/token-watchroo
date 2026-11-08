package tokenwatchroo.providers

import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import hedgehog.*
import hedgehog.runner.*
import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*
import tokenwatchroo.core.*

/** The Claude Code service name rule (#91): the prefix, NFC, sha256 and the first 8 hex characters. The fixed values
  * were computed with `/usr/bin/shasum -a 256` from made-up folders.
  */
object ClaudeKeychainServiceSpec extends Properties {

  override def tests: List[Test] = List(
    example("SHA-256 of the empty text and of abc are the standard test vectors", testSha256Vectors),
    example("made-up folders give the service names that shasum gives", testKnownServices),
    example("the NFC and the NFD text of a folder give the same service", testKnownNfd),
    property("every service is the prefix and 8 lower-case hex characters", testShape),
    property("NFD and NFC texts give the same service", testNfdNfc),
    property("NFC keeps ASCII text without NUL", testNfcAscii),
    example("NFC rejects a text with a NUL character", testNfcNul),
    property("NFC is idempotent", testNfcIdempotent),
    property("the suffix is the first 8 characters of shasum -a 256 of the NFC text", testShasum).withTests(20),
    property("toggling the trailing slash twice gives the folder back", testToggleTwice),
    example("the slash toggle removes or adds one slash, and / has none", testToggleExamples),
    example("candidates are the folder and then the toggled folder", testCandidates),
  )

  private val Prefix = "Claude Code-credentials-"

  private def dir(text: String): ClaudeConfigDir = ClaudeConfigDir.unsafeFrom(text)

  def testSha256Vectors: Result =
    Result.all(
      List(
        MacText.hex(MacText.sha256(Array.emptyByteArray)) ====
          "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        MacText.hex(MacText.sha256("abc".getBytes(StandardCharsets.UTF_8))) ====
          "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
      )
    )

  def testKnownServices: Result =
    Result.all(
      List(
        ClaudeKeychainService.forConfigDir(dir("/Users/alex/.claude-work")) ====
          Right("Claude Code-credentials-c381b4cb"),
        ClaudeKeychainService.forConfigDir(dir("/Users/alex/.claude-work/")) ====
          Right("Claude Code-credentials-6dad957d"),
        ClaudeKeychainService.forConfigDir(dir("/Users/alex/.claude")) ==== Right("Claude Code-credentials-dbec7fc2"),
      )
    )

  def testKnownNfd: Result =
    Result.all(
      List(
        ClaudeKeychainService.forConfigDir(dir("/Users/alex/Café")) ==== Right("Claude Code-credentials-596eef46"),
        ClaudeKeychainService.forConfigDir(dir("/Users/alex/Café")) ==== Right("Claude Code-credentials-596eef46"),
      )
    )

  /** Pairs of the same letter in NFC and in NFD. */
  private val Pairs: List[(String, String)] =
    List(
      "é" -> "é",
      "ü" -> "ü",
      "ñ" -> "ñ",
      "å" -> "å",
      "한" -> "한",
    )

  private val genPart: Gen[(String, String)] =
    Gen.choice1(
      Gen.alphaNum.map(c => (c.toString, c.toString)),
      Gen.element1(Pairs.head, Pairs.tail*),
    )

  private val genFolder: Gen[ClaudeConfigDir] =
    Gen
      .string(Gen.choice1(Gen.alphaNum, Gen.unicode, Gen.element1('/', '.', ' ', '-')), Range.linear(0, 30))
      .map(rest => dir(s"/$rest"))

  def testShape: Property =
    for {
      folder <- genFolder.log("folder")
    } yield ClaudeKeychainService.forConfigDir(folder) match {
      case Right(service) =>
        Result
          .all(
            List(
              Result.assert(service.startsWith(Prefix)),
              service.length ==== Prefix.length + 8,
              Result.assert(service.drop(Prefix.length).forall(c => (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))),
            )
          )
          .log(service)
      case Left(reason) => Result.failure.log(reason)
    }

  def testNfdNfc: Property =
    for {
      parts <- genPart.list(Range.linear(1, 10)).log("parts")
    } yield {
      val composed   = "/Users/alex/" + parts.map(_._1).mkString
      val decomposed = "/Users/alex/" + parts.map(_._2).mkString
      ClaudeKeychainService.forConfigDir(dir(decomposed)) ==== ClaudeKeychainService.forConfigDir(dir(composed))
    }

  def testNfcAscii: Property =
    for {
      text <- Gen.string(Gen.ascii.map(c => if (c === '\u0000') 'a' else c), Range.linear(0, 40)).log("text")
    } yield MacText.nfc(text) ==== Right(text)

  def testNfcNul: Result =
    MacText.nfc("/Users/alex\u0000/x") ==== Left("contains a NUL character")

  def testNfcIdempotent: Property =
    for {
      parts <- genPart.list(Range.linear(0, 10)).log("parts")
    } yield {
      val text = parts.map(_._2).mkString
      MacText.nfc(text).flatMap(MacText.nfc) ==== MacText.nfc(text)
    }

  private def shasum(text: String): Either[String, String] =
    Processes
      .run(List("/bin/sh", "-c", "printf %s \"$1\" | /usr/bin/shasum -a 256", "sh", text), 10.seconds)
      .unsafeRunSync()
      .leftMap(_.show)
      .map(_.stdout.take(8))

  def testShasum: Property =
    for {
      parts <- genPart.list(Range.linear(1, 12)).log("parts")
    } yield {
      val text = "/Users/alex/" + parts.map(_._2).mkString
      val nfc  = MacText.nfc(text)
      (nfc.flatMap(ClaudeKeychainService.suffix) ==== nfc.flatMap(shasum)).log(nfc.show)
    }

  /** Not for a folder that ends with `//`: one toggle removes one slash, so the second adds none back. */
  def testToggleTwice: Property =
    for {
      folder <- genFolder.map(f => dir(f.value.replaceAll("/+$", "/"))).log("folder")
    } yield ClaudeKeychainService.slashToggled(folder) match {
      case Some(toggled) => ClaudeKeychainService.slashToggled(toggled) ==== folder.some
      case None => folder.value ==== "/"
    }

  def testToggleExamples: Result =
    Result.all(
      List(
        ClaudeKeychainService.slashToggled(dir("/a/b")) ==== dir("/a/b/").some,
        ClaudeKeychainService.slashToggled(dir("/a/b/")) ==== dir("/a/b").some,
        ClaudeKeychainService.slashToggled(dir("/")) ==== none[ClaudeConfigDir],
      )
    )

  def testCandidates: Result =
    Result.all(
      List(
        ClaudeKeychainService.candidates(dir("/Users/alex/.claude-work")) ==== Right(
          List("Claude Code-credentials-c381b4cb", "Claude Code-credentials-6dad957d")
        ),
        ClaudeKeychainService.candidates(dir("/")).map(_.length) ==== Right(1),
      )
    )
}
