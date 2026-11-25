package tokenwatchroo.app

import scala.scalanative.unsafe.*

/** The C API of the static library. See `swift/Sources/CTokenWatchroo/include/tokenwatchroo.h` for the header and the
  * design doc section 7 for the threading contract. Every export ends with the calling thread Unmanaged.
  */
object Exports {

  /** Since Scala Native 0.5.13, `ScalaNativeInit` ends with the thread Unmanaged and the guard that the compiler adds to
    * every export enters Managed, so this skips the yield and only performs the epilogue. #119 removes the manual
    * switches.
    */
  @exported("tw_start")
  def start(configJson: CString, callback: CFuncPtr2[CString, Ptr[Byte], Unit], ctx: Ptr[Byte]): CInt = {
    val result =
      try AppRuntime.start(fromCString(configJson), callback, ctx)
      catch { case t: Throwable => Entry.report(t) }
    GcState.set(MutatorState.Unmanaged)
    result
  }

  @exported("tw_refresh")
  def refresh(): CInt = Entry.managed(AppRuntime.refresh())

  @exported("tw_set_config")
  def setConfig(configJson: CString): CInt = Entry.managed(AppRuntime.setConfig(fromCString(configJson)))

  @exported("tw_shutdown")
  def shutdown(): CInt = Entry.managed(AppRuntime.shutdown())
}
