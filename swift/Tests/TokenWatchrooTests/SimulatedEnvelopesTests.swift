import XCTest
@testable import TokenWatchroo

final class SimulatedEnvelopesTests: XCTestCase {

    private func temporaryDirectory() throws -> URL {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("token-watchroo-envelopes-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    func testTheVariableMustHoldAnAbsolutePath() {
        XCTAssertNil(SimulatedEnvelopes.file(environment: [:]))
        XCTAssertNil(SimulatedEnvelopes.file(environment: [SimulatedEnvelopes.envVar: ""]))
        XCTAssertNil(SimulatedEnvelopes.file(environment: [SimulatedEnvelopes.envVar: "relative/file"]))
        XCTAssertEqual(SimulatedEnvelopes.file(environment: [SimulatedEnvelopes.envVar: "/tmp/envelopes"]),
                       URL(fileURLWithPath: "/tmp/envelopes"))
    }

    func testLinesAreSplitOnNewlinesWithoutDecoding() throws {
        let directory = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appendingPathComponent("envelopes")
        try (Data("a\r\n\n".utf8) + Data([0x7B, 0xFF, 0x7D]) + Data("\nb".utf8)).write(to: file)

        XCTAssertEqual(SimulatedEnvelopes.lines(in: file),
                       [Data("a".utf8), Data([0x7B, 0xFF, 0x7D]), Data("b".utf8)])
    }

    func testAMissingFileGivesNoLines() throws {
        let directory = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }

        XCTAssertEqual(SimulatedEnvelopes.lines(in: directory.appendingPathComponent("never-written")), [])
    }
}
