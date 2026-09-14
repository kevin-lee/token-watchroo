package tokenwatchroo.app

import cats.syntax.all.*
import tokenwatchroo.providers.Env

class SimulatedEntryFailureSpec extends munit.FunSuite {

  /** A flag file in a new temp directory, written only when `content` is present. */
  private def flag(content: Option[String]): os.Path = {
    val dir  = os.temp.dir(prefix = "tw-entry-failure")
    val file = dir / "flag"
    content.foreach(text => os.write(file, text))
    file
  }

  test("a flag file holding fail throws the simulated failure") {
    intercept[SimulatedEntryFailure.Failure](SimulatedEntryFailure.check(flag("fail\n".some).some))
  }

  test("other content, a missing file, or no flag file does nothing") {
    SimulatedEntryFailure.check(flag("ok".some).some)
    SimulatedEntryFailure.check(flag(none[String]).some)
    SimulatedEntryFailure.check(none[os.Path])
  }

  test("the variable must hold an absolute path") {
    val file     = flag(none[String])
    val relative = Env.fromMap(Map(SimulatedEntryFailure.EnvVar -> "relative/flag"))
    val absolute = Env.fromMap(Map(SimulatedEntryFailure.EnvVar -> file.toString))
    assertEquals(SimulatedEntryFailure.flagFile(Env.fromMap(Map.empty)), none[os.Path])
    assertEquals(SimulatedEntryFailure.flagFile(relative), none[os.Path])
    assertEquals(SimulatedEntryFailure.flagFile(absolute), file.some)
  }

  test("Entry.report turns the simulated failure into the internal error code") {
    assertEquals(Entry.report(new SimulatedEntryFailure.Failure), Entry.Internal)
  }
}
