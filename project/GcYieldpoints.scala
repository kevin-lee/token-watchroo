import sbt.State

import scala.util.Try

/* Sets the GC yieldpoint mode inside the build (#72). Every binary of this build must be linked with conditional GC
 * yieldpoints, because the Scala Native 0.5.12 GC loses a root of a thread stopped by a trap-based yieldpoint and frees
 * live objects (#44, upstream scala-native/scala-native#5046). Scala Native reads the mode only from
 * SCALANATIVE_GC_TRAP_BASED_YIELDPOINTS, through sys.env, for every link, and it links inside the sbt server, so the value
 * that counts is the one in the sbt server's own environment. checkOnLoad, the Global / onLoad check in build.sbt, keeps
 * 0, puts 0 into the map behind System.getenv() when the variable is not set and reads it back through sys.env, and
 * refuses any other value unless TW_ALLOW_TRAP_YIELDPOINTS=1 opts out. The write needs
 * --add-opens=java.base/java.util=ALL-UNNAMED, which .sbtopts adds whenever sbt starts through the sbt script, and the
 * load is refused when it fails. The value is visible only inside this JVM: a child process gets the unchanged native
 * environment unless its ProcessBuilder environment is touched, and no native code reads the variable. build.sbt says
 * when to remove this file. */
object GcYieldpoints {

  val ModeVariable: String       = "SCALANATIVE_GC_TRAP_BASED_YIELDPOINTS"
  val TrapOptOutVariable: String = "TW_ALLOW_TRAP_YIELDPOINTS"
  val AddOpensOption: String     = "--add-opens=java.base/java.util=ALL-UNNAMED"

  enum TrapOptOut {
    case Given
    case Absent
  }

  enum Decision {
    case KeepConditional
    case SetConditional(optOut: TrapOptOut)
    case AllowByOptOut(value: String)
    case Refuse(value: String)
  }

  enum Refusal {
    case ExplicitValue(value: String)
    case CouldNotSet(cause: String)
  }

  def trapOptOut(env: Map[String, String]): TrapOptOut =
    if (env.get(TrapOptOutVariable).contains("1")) TrapOptOut.Given else TrapOptOut.Absent

  /* Under the opt-out every explicit value, 0 included, is allowed with a warning, because the opt-out also turns off
   * the archive gate in stageNativeLib. */
  def decide(mode: Option[String], optOut: TrapOptOut): Decision =
    mode match {
      case None => Decision.SetConditional(optOut)
      case Some(value) =>
        optOut match {
          case TrapOptOut.Given => Decision.AllowByOptOut(value)
          case TrapOptOut.Absent =>
            value match {
              case "0" => Decision.KeepConditional
              case _ => Decision.Refuse(value)
            }
        }
    }

  /* Scala Native's rule: "1" means trap-based, any other value conditional. */
  def linkedMode(value: String): String =
    value match {
      case "1" => "trap-based"
      case _ => "conditional"
    }

  private def failedTo(step: String)(error: Throwable): String = s"failed to $step: $error"

  /* Puts name=value into the map behind System.getenv(), then reads it back through sys.env, the call Scala Native
   * makes for every link. The Left names the step that failed. */
  def setInThisJvm(name: String, value: String): Either[String, Unit] = {
    val view = System.getenv()
    for {
      field      <- Try(view.getClass.getDeclaredField("m")).toEither.left.map(failedTo("find the backing map"))
      accessible <- Try(field.trySetAccessible()).toEither.left.map(failedTo("open the backing map"))
      _          <- Either.cond(accessible, (), s"java.util is not open to the build, $AddOpensOption is missing")
      backing    <- Try(field.get(view).asInstanceOf[java.util.Map[String, String]])
                      .toEither
                      .left
                      .map(failedTo("read the backing map"))
      _          <- Try(backing.put(name, value)).toEither.left.map(failedTo("write the backing map"))
      _          <- Either.cond(
                      sys.env.get(name).contains(value),
                      (),
                      s"$name reads ${sys.env.get(name).fold("as not set")(v => s"'$v'")} after the write",
                    )
    } yield ()
  }

  val SetLine: String =
    s"$ModeVariable was not set, so the build set it to 0 in the environment of this sbt server: every link uses " +
      "conditional GC yieldpoints (#44, #72)."

  val OptOutWithoutValueLine: String =
    s"$TrapOptOutVariable=1 is set but $ModeVariable was not, so this server links with conditional yieldpoints. " +
      s"A trap build needs $ModeVariable=1 as well (#72)."

  def optOutLine(value: String): String =
    s"$TrapOptOutVariable=1: $ModeVariable is '$value', so this server links with ${linkedMode(value)} yieldpoints, " +
      "and stageNativeLib does not check the archive's mode (#44)."

  def refusalMessage(refusal: Refusal): String = {
    val reason = refusal match {
      case Refusal.ExplicitValue(value) =>
        List(
          value match {
            case "1" =>
              s"$ModeVariable is '1', so Scala Native would link with trap-based yieldpoints, which the 0.5.12 GC " +
                "corrupts (#44)."
            case _ =>
              s"$ModeVariable is '$value'. This build accepts only 0, or no value, which it replaces with 0, because " +
                "the 0.5.12 GC corrupts trap-based yieldpoints (#44)."
          },
          s"Fix: unset $ModeVariable or set it to 0 in the environment that starts the sbt server, run " +
            "`sbt --client shutdown`, then start a fresh `sbt`.",
        )
      case Refusal.CouldNotSet(cause) =>
        List(
          s"$ModeVariable is not set, so Scala Native would link with trap-based yieldpoints, which the 0.5.12 GC " +
            "corrupts (#44).",
          s"The build could not set it in the environment of this sbt server ($cause). That needs the JVM option " +
            s"$AddOpensOption, which .sbtopts adds when sbt starts through the sbt script (#72).",
          s"Fix: start sbt through the sbt script, or export $ModeVariable=0 in the environment that starts the sbt " +
            "server, run `sbt --client shutdown`, then start a fresh `sbt`.",
        )
    }
    (reason ++ List(
      "A target linked in the other mode never relinks for the variable: remove the native and native-test folders " +
        "under target/out/native0.5/scala-3.8.4/*/ first.",
      s"To build with trap-based yieldpoints on purpose, set both $ModeVariable=1 and $TrapOptOutVariable=1.",
    )).mkString("\n")
  }

  /* The Global / onLoad check in build.sbt. It throws, as sbt needs, to fail the load. */
  def checkOnLoad(state: State): State = {
    val env = sys.env
    decide(env.get(ModeVariable), trapOptOut(env)) match {
      case Decision.KeepConditional =>
        state

      case Decision.SetConditional(optOut) =>
        setInThisJvm(ModeVariable, "0").fold(
          cause => sys.error(refusalMessage(Refusal.CouldNotSet(cause))),
          _ => {
            state.log.info(SetLine)
            optOut match {
              case TrapOptOut.Given => state.log.warn(OptOutWithoutValueLine)
              case TrapOptOut.Absent => ()
            }
            state
          },
        )

      case Decision.AllowByOptOut(value) =>
        state.log.warn(optOutLine(value))
        state

      case Decision.Refuse(value) =>
        sys.error(refusalMessage(Refusal.ExplicitValue(value)))
    }
  }
}
