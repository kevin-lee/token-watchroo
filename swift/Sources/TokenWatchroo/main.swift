import AppKit

// stderr first, so everything the library writes from `ScalaNativeInit` on lands in the log file.
let logFile = LogFile.redirectStandardError()
let version = About.version(infoDictionary: Bundle.main.infoDictionary)
let arch = About.architectureLabel(.current, translated: About.isTranslated())
Log.app.notice("started pid=\(ProcessInfo.processInfo.processIdentifier) version=\(version) arch=\(arch) logFile=\(logFile?.path ?? "stderr")")

// The shell is deliberately dumb: it hosts the Scala Native library, renders what it sends, and forwards clicks.
let application = NSApplication.shared
application.setActivationPolicy(.accessory)
let delegate = AppDelegate()
application.delegate = delegate
application.run()
