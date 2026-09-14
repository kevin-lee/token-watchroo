import OSLog
import XCTest
@testable import TokenWatchroo

final class LoggingTests: XCTestCase {

    /// Entries of this process under `Log.subsystem` whose text contains `marker`, polled every 100 ms for up to 3 s.
    private func entries(containing marker: String) throws -> [OSLogEntryLog] {
        let deadline = Date().addingTimeInterval(3)
        while true {
            let store = try OSLogStore(scope: .currentProcessIdentifier)
            let found = try store.getEntries(at: store.position(timeIntervalSinceLatestBoot: 0))
                .compactMap { $0 as? OSLogEntryLog }
                .filter { $0.subsystem == Log.subsystem && $0.composedMessage.contains(marker) }
            if !found.isEmpty || Date() >= deadline { return found }
            Thread.sleep(forTimeInterval: 0.1)
        }
    }

    /// `lastSeq` starts at -1 and no test applies an envelope, so a negative `seq` is always stale.
    @MainActor
    func testAStaleEnvelopeIsLoggedThroughTheFacade() throws {
        let seq = Int64.random(in: -1_000_000_000 ... -2)
        let json = #"{"version":1,"type":"snapshot","seq":\#(seq),"data":{"updatedAt":1,"menubar":{"kind":"unavailable"}}}"#
        Core.shared.handle(Data(json.utf8))

        let found = try entries(containing: "reason=stale-seq type=snapshot seq=\(seq) lastSeq=")
        XCTAssertEqual(found.count, 1)
        XCTAssertEqual(found.first?.category, "core")
    }

    @MainActor
    func testAnUndecodableBodyIsLoggedAndDoesNotAdvanceTheSeq() throws {
        let seq = Int64.random(in: 1_000_000_000 ... 2_000_000_000)
        let body = #""data":{"updatedAt":1}}"#
        Core.shared.handle(Data(#"{"version":1,"type":"snapshot","seq":\#(seq),\#(body)"#.utf8))

        let found = try entries(containing: "reason=undecodable-body type=snapshot seq=\(seq) path=data.menubar")
        XCTAssertEqual(found.count, 1)

        Core.shared.handle(Data(#"{"version":1,"type":"snapshot","seq":-3,\#(body)"#.utf8))
        XCTAssertFalse(try entries(containing: "reason=stale-seq type=snapshot seq=-3 lastSeq=-1").isEmpty)
    }

    func testLogFilePrepareRotatesOnlyAboveTheBound() throws {
        let fileManager = FileManager.default
        let root = fileManager.temporaryDirectory
            .appendingPathComponent("token-watchroo-log-file-\(UUID().uuidString)", isDirectory: true)
        defer { try? fileManager.removeItem(at: root) }
        let directory = root.appendingPathComponent("Logs/Token Watchroo", isDirectory: true)

        let file = try LogFile.prepare(in: directory, maxBytes: 16)
        XCTAssertEqual(file, directory.appendingPathComponent(LogFile.fileName))
        XCTAssertTrue(fileManager.fileExists(atPath: directory.path))
        XCTAssertFalse(fileManager.fileExists(atPath: file.path))

        let previous = directory.appendingPathComponent(LogFile.previousFileName)
        try Data(repeating: 0x61, count: 10).write(to: file)
        _ = try LogFile.prepare(in: directory, maxBytes: 16)
        XCTAssertEqual(try Data(contentsOf: file).count, 10)
        XCTAssertFalse(fileManager.fileExists(atPath: previous.path))

        try Data("old".utf8).write(to: previous)
        try Data(repeating: 0x62, count: 32).write(to: file)
        _ = try LogFile.prepare(in: directory, maxBytes: 16)
        XCTAssertFalse(fileManager.fileExists(atPath: file.path))
        XCTAssertEqual(try Data(contentsOf: previous), Data(repeating: 0x62, count: 32))
    }

    func testErrorAndNoticeReachTheUnifiedLogAsPublicText() throws {
        let marker = UUID().uuidString
        Log.app.error("error probe \(marker)")
        Log.app.notice("notice probe \(marker)")

        let errors = try entries(containing: "error probe \(marker)")
        XCTAssertEqual(errors.count, 1)
        XCTAssertEqual(errors.first?.level, .error)
        let notices = try entries(containing: "notice probe \(marker)")
        XCTAssertEqual(notices.count, 1)
        XCTAssertEqual(notices.first?.level, .notice)
    }
}
