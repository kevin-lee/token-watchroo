import Foundation
import os

/// The only logging entry point of the shell. Messages go to the unified log under subsystem `io.kevinlee.tokenwatchroo`
/// as public text, because `NSLog` and default interpolations show as `<private>` in `log show`, and to stderr, which is
/// the log file in a bundle (see `LogFile`).
struct Log: Sendable {

    static let subsystem = "io.kevinlee.tokenwatchroo"

    /// Envelopes from the library: dropped envelopes and library errors.
    static let core = Log(category: "core")
    static let notifications = Log(category: "notifications")
    /// Launch at Login.
    static let launch = Log(category: "launch")
    /// Launch, `tw_start`, and the log file.
    static let app = Log(category: "app")

    let category: String
    private let logger: Logger

    private init(category: String) {
        self.category = category
        logger = Logger(subsystem: Log.subsystem, category: category)
    }

    func error(_ message: String) {
        logger.error("\(message, privacy: .public)")
        mirror(level: "error", message)
    }

    func notice(_ message: String) {
        logger.notice("\(message, privacy: .public)")
        mirror(level: "notice", message)
    }

    /// One `write` call per line, because the `stderr` global is not concurrency-safe in Swift 6 mode.
    private func mirror(level: String, _ message: String) {
        let line = "\(Date.now.formatted(.iso8601)) [\(category)] \(level): \(message)\n"
        _ = Array(line.utf8).withUnsafeBytes { write(STDERR_FILENO, $0.baseAddress, $0.count) }
    }
}
