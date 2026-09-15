package tokenwatchroo.app

import scala.scalanative.unsafe.*

/** Bindings to the C envelope recorder in `src/test/resources/scala-native/bridge_spec_recorder.c`. The recorder is C
  * because `Bridge.send` calls it while the worker is Unmanaged, and a Scala function passed as a `CFuncPtr2` boxes its
  * `CString` and `Ptr` arguments on the Scala heap in the generated forwarder while the optimiser is off (#41).
  */
@extern
object BridgeSpecRecorder {

  /** A new empty recorder, or null when malloc fails. */
  @name("tw_test_recorder_new")
  def create(): Ptr[Byte] = extern

  @name("tw_test_recorder_free")
  def free(recorder: Ptr[Byte]): Unit = extern

  /** The C function to pass to `Bridge.make` together with a recorder as `ctx`. */
  @name("tw_test_recorder_callback")
  def callback(): CFuncPtr2[CString, Ptr[Byte], Unit] = extern

  /** How many envelopes were recorded. */
  @name("tw_test_recorder_count")
  def count(recorder: Ptr[Byte]): CSize = extern

  /** Bytes recorded: every envelope followed by a newline. */
  @name("tw_test_recorder_length")
  def length(recorder: Ptr[Byte]): CSize = extern

  /** 1 when growing the buffer failed and an envelope was dropped, otherwise 0. */
  @name("tw_test_recorder_failed")
  def failed(recorder: Ptr[Byte]): CInt = extern

  /** Copies up to `capacity` recorded bytes into `dest` and returns how many were copied. */
  @name("tw_test_recorder_copy")
  def copy(recorder: Ptr[Byte], dest: Ptr[Byte], capacity: CSize): CSize = extern

  /** Copies up to `capacity` FNV-1a 64 hashes, one per envelope as it arrived, into `dest`. */
  @name("tw_test_recorder_copy_hashes")
  def copyHashes(recorder: Ptr[Byte], dest: Ptr[Long], capacity: CSize): CSize = extern

  /** Copies up to `capacity` FNV-1a 64 hashes, one per envelope as the callback ended, into `dest`. */
  @name("tw_test_recorder_copy_exit_hashes")
  def copyExitHashes(recorder: Ptr[Byte], dest: Ptr[Long], capacity: CSize): CSize = extern
}
