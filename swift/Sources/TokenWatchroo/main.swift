import AppKit

// The shell is deliberately dumb: it hosts the Scala Native library, renders what it sends, and forwards clicks.
let application = NSApplication.shared
application.setActivationPolicy(.accessory)
let delegate = AppDelegate()
application.delegate = delegate
application.run()
