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
        Result.assert(received.get() == Integer.valueOf(42)).log(s"poll got ${String.valueOf(received.get())}"),
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

    val pollers = (0 until PollerCount).toList.map { n =>
      startDaemon(s"sq-poller-$n") { () =>
        try {
          var i = 0
          while (!stop.get()) {
            val nanos = PollTimeoutNanos(i % PollTimeoutNanos.length)
            if (queue.poll(nanos, TimeUnit.NANOSECONDS) != null) {
              val _ = handOffs.incrementAndGet()
            }
            i += 1
          }
        } catch { case t: Throwable => record(t) }
      }
    }

    val start    = System.currentTimeMillis()
    val offerers = (0 until OffererCount).toList.map { n =>
      startDaemon(s"sq-offerer-$n") { () =>
        try {
          var i = 0
          while (!stop.get() && System.currentTimeMillis() - start < OfferMillis) {
            val _ = queue.offer(Integer.valueOf(i))
            val _ = offers.incrementAndGet()
            i += 1
          }
        } catch { case t: Throwable => record(t) }
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
