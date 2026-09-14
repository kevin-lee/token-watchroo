import AppKit

// stderr first, so everything the library writes from `ScalaNativeInit` on lands in the log file.
let logFile = LogFile.redirectStandardError()
let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "unbundled"
Log.app.notice("started pid=\(ProcessInfo.processInfo.processIdentifier) version=\(version) logFile=\(logFile?.path ?? "stderr")")

// The shell is deliberately dumb: it hosts the Scala Native library, renders what it sends, and forwards clicks.
let application = NSApplication.shared
application.setActivationPolicy(.accessory)
let delegate = AppDelegate()
application.delegate = delegate
application.run()
