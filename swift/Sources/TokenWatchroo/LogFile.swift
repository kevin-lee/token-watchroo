import Darwin
import Foundation

/// Keeps stderr of a bundled run in `~/Library/Logs/Token Watchroo/token-watchroo.log`. A bundle started with `open` has
/// stderr on `/dev/null`, which loses `Entry.report` and Scala Native runtime output.
enum LogFile {

    static let fileName = "token-watchroo.log"
    static let previousFileName = "token-watchroo.log.1"
    /// Above this size at launch the log file is moved to `previousFileName`.
    static let maxBytes: UInt64 = 1_048_576

    static func defaultDirectory() -> URL {
        let library = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask).first
            ?? FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library")
        return library.appendingPathComponent("Logs/Token Watchroo", isDirectory: true)
    }

    /// Creates `directory`, moves a log file above `maxBytes` to `previousFileName`, and returns the log file URL
    /// without creating the file.
    static func prepare(in directory: URL, maxBytes: UInt64) throws -> URL {
        let fileManager = FileManager.default
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        let file = directory.appendingPathComponent(fileName)
        let previous = directory.appendingPathComponent(previousFileName)
        let size = (try? fileManager.attributesOfItem(atPath: file.path)[.size] as? NSNumber)?.uint64Value
        if let size, size > maxBytes {
            if fileManager.fileExists(atPath: previous.path) {
                try fileManager.removeItem(at: previous)
            }
            try fileManager.moveItem(at: file, to: previous)
        }
        return file
    }

    /// Points fd 2 at the log file for a bundled run whose stderr is not a terminal. Returns the log file, or nil when
    /// stderr is left as it is.
    static func redirectStandardError() -> URL? {
        guard Bundle.main.bundleIdentifier != nil, isatty(STDERR_FILENO) == 0 else { return nil }
        let file: URL
        do {
            file = try prepare(in: defaultDirectory(), maxBytes: maxBytes)
        } catch {
            Log.app.error("log file unavailable: \(error.localizedDescription)")
            return nil
        }
        let descriptor = open(file.path, O_WRONLY | O_CREAT | O_APPEND, 0o644)
        guard descriptor != -1 else {
            Log.app.error("log file unavailable: open \(file.path): \(String(cString: strerror(errno)))")
            return nil
        }
        defer { close(descriptor) }
        guard dup2(descriptor, STDERR_FILENO) != -1 else {
            Log.app.error("log file unavailable: dup2: \(String(cString: strerror(errno)))")
            return nil
        }
        return file
    }
}
