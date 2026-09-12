package tokenwatchroo.app

/** The wrapper around every exported entry point, the mirror image of what the compiler emits around a `@blocking`
  * extern call. The host thread arrives Unmanaged (it parks in the AppKit run loop between calls), so the wrapper
  * yields to any collection in progress, switches to Managed, runs the body, and always leaves Unmanaged again. It is
  * `inline` with an inline body so nothing is allocated before the switch, and it catches every `Throwable` inside the
  * managed region so no exception can escape into Swift frames.
  */
object Entry {

  val Ok: Int          = 0
  val BadConfig: Int   = 1
  val Internal: Int    = 2
  val WrongThread: Int = 3

  inline def managed(inline body: Int): Int = {
    GcState.yieldNow()
    GcState.set(MutatorState.Managed)
    val result =
      try body
      catch { case t: Throwable => report(t) }
    GcState.set(MutatorState.Unmanaged)
    result
  }

  /** Runs inside the managed region. */
  def report(t: Throwable): Int = {
    System
      .err
      .println(s"[token-watchroo] entry point failed: ${t.getClass.getName}: ${Option(t.getMessage).getOrElse("")}")
    Internal
  }
}
