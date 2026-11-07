package tokenwatchroo.core

import hedgehog.*
import hedgehog.runner.*

import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}
import java.util.concurrent.{SynchronousQueue, TimeUnit}

/** Checks the corrected `java.util.concurrent.SynchronousQueue` in this module (#83), which the linker takes ahead of
  * javalib's.
  */
object SynchronousQueueSpec extends Properties {

  override def tests: List[Test] = List(
    example("offer hands the element to a waiting poll", testHandOff),
    example("offer never throws while timed polls time out (#83)", testOfferAgainstTimingOutPolls),
  )

  private val PollerCount      = 4
  private val OffererCount     = 2
  private val OfferMillis      = 3_000L
  private val JoinMillis       = 30_000L
  private val PollTimeoutNanos = List(0L, 500L, 1_000L, 2_000L, 5_000L, 20_000L)

  def testHandOff: Result = {
    val queue    = new SynchronousQueue[Integer](false)
    val received = new AtomicReference[Integer]()
    val poller   = startDaemon("sq-poller-0") { () =>
      received.set(queue.poll(10L, TimeUnit.SECONDS))
    }
    val deadline = System.currentTimeMillis() + 10_000L

    @annotation.tailrec
    def offerUntilTaken(): Boolean =
      if (queue.offer(Integer.valueOf(42))) true
      else if (System.currentTimeMillis() >= deadline) false
      else offerUntilTaken()

    val offered = offerUntilTaken()
    poller.join(10_000L)
    Result.all(
      List(
        Result.assert(offered).log("offer returned true"),
        (Option(received.get()).map(_.intValue) ==== Some(42)).log("poll got the offered element"),
      )
    )
  }

  def testOfferAgainstTimingOutPolls: Result = {
    val queue    = new SynchronousQueue[Integer](false)
    val failure  = new AtomicReference[Throwable]()
    val stop     = new AtomicBoolean(false)
    val offers   = new AtomicLong(0L)
    val handOffs = new AtomicLong(0L)

    def record(t: Throwable): Unit = {
      val _ = failure.compareAndSet(null, t)
      stop.set(true)
    }

    /* `poll` is a Java API that returns `null` when it times out, and the check stays `!= null` on purpose: this loop
     * is the race of #83, and `Option(...)` would allocate a `Some` for each hand-off, which adds GC work that the
     * race does not need. */
    @annotation.tailrec
    def pollUntilStopped(turn: Int): Unit =
      if (stop.get()) ()
      else {
        val nanos = PollTimeoutNanos(turn % PollTimeoutNanos.length)
        if (queue.poll(nanos, TimeUnit.NANOSECONDS) != null) {
          val _ = handOffs.incrementAndGet()
        } else ()
        pollUntilStopped(turn + 1)
      }

    val pollers = (0 until PollerCount).toList.map { n =>
      startDaemon(s"sq-poller-$n") { () =>
        try pollUntilStopped(0)
        catch { case t: Throwable => record(t) }
      }
    }

    val start = System.currentTimeMillis()

    @annotation.tailrec
    def offerUntilStopped(turn: Int): Unit =
      if (stop.get() || System.currentTimeMillis() - start >= OfferMillis) ()
      else {
        val _ = queue.offer(Integer.valueOf(turn))
        val _ = offers.incrementAndGet()
        offerUntilStopped(turn + 1)
      }

    val offerers = (0 until OffererCount).toList.map { n =>
      startDaemon(s"sq-offerer-$n") { () =>
        try offerUntilStopped(0)
        catch { case t: Throwable => record(t) }
      }
    }

    offerers.foreach(_.join(JoinMillis))
    stop.set(true)
    pollers.foreach(_.join(JoinMillis))

    val counts = s"offers=${offers.get()} handOffs=${handOffs.get()}"
    Result
      .all(
        List(
          Result.assert(Option(failure.get()).isEmpty).log(describe(Option(failure.get()))),
          Result.assert(handOffs.get() > 0L).log(s"no hand-off happened, so the race was not exercised: $counts"),
        )
      )
      .log(counts)
  }

  private def describe(failure: Option[Throwable]): String =
    failure.fold("no failure") { t =>
      val frames = t.getStackTrace.take(5).map(frame => s"  at $frame").mkString("\n")
      s"${t.getClass.getName}: ${String.valueOf(t.getMessage)}\n$frames"
    }

  private def startDaemon(name: String)(body: () => Unit): Thread = {
    val thread = new Thread(() => body(), name)
    thread.setDaemon(true)
    thread.start()
    thread
  }

}
