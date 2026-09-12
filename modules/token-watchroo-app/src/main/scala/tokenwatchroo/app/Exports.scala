package tokenwatchroo.app

import scala.scalanative.unsafe.*

/** The C API of the static library. See `swift/Sources/CTokenWatchroo/include/tokenwatchroo.h` for the header and the
  * design doc section 7 for the threading contract. Every export ends with the calling thread Unmanaged.
  */
object Exports {

  /** Entered Managed, right after `ScalaNativeInit`, so it skips the yield and only performs the epilogue. */
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
