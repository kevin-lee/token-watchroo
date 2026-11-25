//> using scala 3.8.4
//> using platform scala-native
//> using nativeVersion 0.5.13
//> using nativeMode release-fast
//> using nativeGc commix
//> using nativeLto none
//> using nativeMultithreading true

/* One thread calls System.gc() in a loop. The main thread starts a short-lived thread, waits for it, and starts the
 * next one. On x86_64 macOS with trap-based GC yieldpoints, a short-lived thread dies with SIGILL in _sigtramp.
 *
 * Usage: minimal [seconds=20] [child=empty]. child is what each short-lived thread does:
 *   empty  nothing
 *   alloc  allocates a 1 KiB array
 *   sleep  calls usleep(100) through a @blocking binding
 */

import java.util.concurrent.atomic.AtomicBoolean

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

@extern
object LibC {
  @blocking def usleep(microseconds: CUnsignedInt): CInt = extern
}

object Minimal {

  @volatile private var sink: Array[Byte] = null

  def main(args: Array[String]): Unit = {
    val seconds = args.headOption.flatMap(_.toIntOption).getOrElse(20)
    val child: Runnable = args.lift(1).getOrElse("empty") match {
      case "empty" => () => ()
      case "alloc" => () => sink = new Array[Byte](1024)
      case "sleep" => () => { val _ = LibC.usleep(100.toUInt) }
      case other =>
        System.err.println(s"unknown child: $other")
        sys.exit(2)
    }

    val stop      = AtomicBoolean(false)
    val collector = new Thread(() => while (!stop.get()) System.gc())
    collector.start()

    val deadline = System.nanoTime() + seconds * 1000000000L
    var started  = 0L
    while (System.nanoTime() < deadline) {
      val thread = new Thread(child)
      thread.start()
      thread.join()
      started += 1
    }

    stop.set(true)
    collector.join()
    println(s"seconds=$seconds child=${args.lift(1).getOrElse("empty")} started=$started")
  }
}
