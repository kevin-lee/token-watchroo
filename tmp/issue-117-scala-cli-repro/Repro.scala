//> using scala 3.8.4
//> using platform scala-native
//> using nativeVersion 0.5.13
//> using nativeMode release-fast
//> using nativeGc commix
//> using nativeLto none
//> using nativeMultithreading true

/* Forced-collection storm for the x86_64 macOS SIGILL and hangs with trap-based GC yieldpoints in Scala Native 0.5.13
 * (scala-native/scala-native#5393). It copies the shape of token-watchroo's BridgeSpec and CurlBufferSpec with plain
 * threads and no libraries:
 *
 *   - 1 thread calls System.gc() in a loop.
 *   - 4 threads allocate and check arrays that they keep alive across collections.
 *   - 1 thread calls libc usleep through a @blocking binding in a loop, so it is Unmanaged most of the time.
 *   - 2 threads hand items through a SynchronousQueue with timed polls, as cats-effect's blocking pool does.
 *   - 1 thread starts short-lived threads, because the faulting frame in the crash reports is under
 *     NativeThread.threadEntryPoint.
 *
 * The yieldpoint mode comes from SCALANATIVE_GC_TRAP_BASED_YIELDPOINTS (1 or 0) in the environment at link time.
 *
 * Usage: repro [seconds]. Exit 0: no fault seen. Exit 3: an array lost its contents. A SIGILL ends the process with
 * 132 from the shell. A hang is caught by the caller's timeout.
 */

import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

@extern
object LibC {
  @blocking def usleep(microseconds: CUnsignedInt): CInt = extern
}

object Repro {

  private val stop        = AtomicBoolean(false)
  private val collections = AtomicLong(0L)
  private val allocations = AtomicLong(0L)
  private val sleeps      = AtomicLong(0L)
  private val handoffs    = AtomicLong(0L)
  private val spawned     = AtomicLong(0L)
  private val corrupted   = AtomicLong(0L)

  private def worker(name: String)(body: => Unit): Thread = {
    val thread = new Thread(() => body, name)
    thread.start()
    thread
  }

  private def collector(): Unit =
    while (!stop.get()) {
      System.gc()
      collections.incrementAndGet()
    }

  /* Each slot holds an array filled with one value. The thread checks a slot before it replaces it, so a collection
   * that frees a live array shows as a wrong value. */
  private def allocator(seed: Int): Unit = {
    val slots = new Array[Array[Int]](256)
    var i     = 0
    while (!stop.get()) {
      val index = i & 255
      val old   = slots(index)
      if (old != null) {
        val expected = old.length ^ seed
        var j        = 0
        while (j < old.length) {
          if (old(j) != expected) {
            corrupted.incrementAndGet()
            j = old.length
          } else j += 1
        }
      }
      val size  = 16 + ((i * 7919) % 4096)
      val fresh = Array.fill(size)(size ^ seed)
      slots(index) = fresh
      /* Short-lived garbage between the kept arrays. */
      val _     = List.tabulate(32)(k => (k + i).toString)
      allocations.incrementAndGet()
      i += 1
    }
  }

  private def sleeper(): Unit =
    while (!stop.get()) {
      LibC.usleep(200.toUInt)
      sleeps.incrementAndGet()
    }

  private def producer(queue: SynchronousQueue[Array[Byte]]): Unit =
    while (!stop.get()) {
      if (queue.offer(new Array[Byte](64), 1, TimeUnit.MILLISECONDS)) handoffs.incrementAndGet()
    }

  private def consumer(queue: SynchronousQueue[Array[Byte]]): Unit =
    while (!stop.get()) {
      val _ = queue.poll(1, TimeUnit.MILLISECONDS)
    }

  private def spawner(): Unit =
    while (!stop.get()) {
      val child = new Thread(() => {
        val _ = List.tabulate(64)(_.toString).mkString
        val _ = LibC.usleep(100.toUInt)
      })
      child.start()
      child.join()
      spawned.incrementAndGet()
    }

  def main(args: Array[String]): Unit = {
    val seconds = args.headOption.flatMap(_.toIntOption).getOrElse(20)
    val queue   = new SynchronousQueue[Array[Byte]]()
    val threads =
      List(
        worker("collector")(collector()),
        worker("sleeper")(sleeper()),
        worker("producer")(producer(queue)),
        worker("consumer")(consumer(queue)),
        worker("spawner")(spawner()),
      ) ++ (1 to 4).map(n => worker(s"allocator-$n")(allocator(n)))

    Thread.sleep(seconds * 1000L)
    stop.set(true)
    threads.foreach(_.join())

    println(
      s"seconds=$seconds collections=${collections.get()} allocations=${allocations.get()} sleeps=${sleeps.get()} " +
        s"handoffs=${handoffs.get()} spawned=${spawned.get()} corrupted=${corrupted.get()}"
    )
    if (corrupted.get() != 0L) System.exit(3)
  }
}
