import Foundation

/// The Scala Native garbage collector settings the app runs with (#65). `ScalaNativeInit()` reads them from the
/// environment when it sets up the heap, so `Core.start` applies them right before that call. A variable that is
/// already set, for example with `open --env` for diagnostics, is kept.
///
/// `GC_MAXIMUM_HEAP_SIZE=512M`: without a cap commix may grow its heap to the machine's whole memory, as it did in the
/// storm test suites. The app's heap measured 17.9 MB after 2.5 hours of normal use, so 512 MiB leaves room for about
/// 28 times that. At the cap the heap stops growing, and a genuine need beyond it ends the app with `Out of heap space`
/// in its log file instead of taking memory from the rest of the machine.
enum GarbageCollectorSettings {

    static let defaults: [String: String] = ["GC_MAXIMUM_HEAP_SIZE": "512M"]

    /// Sets each default that the environment does not hold yet.
    static func apply() {
        for (name, value) in defaults {
            setenv(name, value, 0)
        }
    }

    /// The variable's current value, or nil when it is not set.
    static func current(_ name: String) -> String? {
        getenv(name).map { String(cString: $0) }
    }
}
