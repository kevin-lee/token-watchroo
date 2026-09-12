package tokenwatchroo.app

import scala.scalanative.unsafe.*

/** Public C entry points of the Scala Native garbage collector (`gc/shared/ScalaNativeGC.h`). A thread in the
  * `Unmanaged` state is ignored by stop-the-world collections. Never annotated `@blocking`: these calls are what the
  * state switch itself is made of.
  */
@extern
object GcState {
  @name("scalanative_GC_set_mutator_thread_state")
  def set(state: CInt): Unit = extern

  @name("scalanative_GC_yield")
  def yieldNow(): Unit = extern
}

object MutatorState {
  val Managed: CInt   = 0
  val Unmanaged: CInt = 1
}
