import XCTest
@testable import TokenWatchroo

final class EnvelopeDecoderTests: XCTestCase {

    /// Captured with lldb from the library callback when no agent was detected (issue #32).
    private static let zeroAgentEnvelope =
        #"{"version":1,"type":"snapshot","seq":35,"data":{"updatedAt":1789358264,"menubar":{"kind":"unavailable"}}}"#

    private static let snapshotWithoutMenubar = #"{"version":1,"type":"snapshot","seq":7,"data":{"updatedAt":1}}"#

    private func decode(_ json: String, after lastSeq: Int64 = -1) -> Result<DecodedEnvelope, EnvelopeDropReason> {
        EnvelopeDecoder.decode(Data(json.utf8), after: lastSeq)
    }

    private func reason(_ result: Result<DecodedEnvelope, EnvelopeDropReason>) -> EnvelopeDropReason? {
        if case .failure(let reason) = result { return reason }
        return nil
    }

    func testBytesThatAreNotUTF8AreDropped() throws {
        let result = EnvelopeDecoder.decode(Data([0x7B, 0xFF, 0x7D]), after: -1)
        XCTAssertEqual(try XCTUnwrap(reason(result)), .notUTF8(byteCount: 3))
    }

    func testTextThatIsNotJSONIsAnUndecodableHead() throws {
        guard case .undecodableHead(_, let prefix) = try XCTUnwrap(reason(decode("not json"))) else {
            return XCTFail("expected an undecodable head")
        }
        XCTAssertEqual(prefix, "not json")
    }

    func testAHeadWithoutTypeIsAnUndecodableHead() throws {
        guard case .undecodableHead(let detail, _) = try XCTUnwrap(reason(decode(#"{"version":1,"seq":3}"#))) else {
            return XCTFail("expected an undecodable head")
        }
        XCTAssertEqual(detail, "missing key")
    }

    func testASeqNotAboveTheLastAppliedIsStale() throws {
        XCTAssertEqual(try XCTUnwrap(reason(decode(Self.zeroAgentEnvelope, after: 35))),
                       .staleSeq(type: "snapshot", seq: 35, lastSeq: 35))
        XCTAssertEqual(try XCTUnwrap(reason(decode(Self.zeroAgentEnvelope, after: 36))),
                       .staleSeq(type: "snapshot", seq: 35, lastSeq: 36))
    }

    func testASnapshotWithoutMenubarIsAnUndecodableBody() throws {
        XCTAssertEqual(try XCTUnwrap(reason(decode(Self.snapshotWithoutMenubar))),
                       .undecodableBody(type: "snapshot", seq: 7, path: "data.menubar", detail: "missing key"))
    }

    func testAnUnknownAgentStatusReportsTheIndexedPath() throws {
        let json = #"{"version":1,"type":"snapshot","seq":8,"data":{"updatedAt":1,"agents":[{"id":"codex","status":"bogus","fetchedAt":1}],"menubar":{"kind":"unavailable"}}}"#
        guard case .undecodableBody(let type, _, let path, _) = try XCTUnwrap(reason(decode(json))) else {
            return XCTFail("expected an undecodable body")
        }
        XCTAssertEqual(type, "snapshot")
        XCTAssertEqual(path, "data.agents[0].status")
    }

    func testAnAlertWithoutTitleIsAnUndecodableBody() throws {
        let json = #"{"version":1,"type":"alert","seq":12,"data":{"agent":"codex","window":"session","kind":"threshold80","windowResetsAt":1789187040,"usedPercent":82.0,"body":"82% of the 5 h window used.","identifier":"codex.session.1789187040.threshold80"}}"#
        guard case .undecodableBody(let type, _, let path, _) = try XCTUnwrap(reason(decode(json))) else {
            return XCTFail("expected an undecodable body")
        }
        XCTAssertEqual(type, "alert")
        XCTAssertEqual(path, "data.title")
    }

    func testAnErrorEnvelopeWithoutMessageIsAnUndecodableBody() throws {
        XCTAssertEqual(try XCTUnwrap(reason(decode(#"{"version":1,"type":"error","seq":9}"#))),
                       .undecodableBody(type: "error", seq: 9, path: "message", detail: "missing key"))
    }

    func testAnUnknownTypeIsDropped() throws {
        XCTAssertEqual(try XCTUnwrap(reason(decode(#"{"version":1,"type":"future","seq":10}"#))),
                       .unknownType(type: "future", seq: 10))
    }

    func testValidEnvelopesDecode() throws {
        guard case .success(.snapshot(let seq, let snapshot)) = decode(Self.zeroAgentEnvelope) else {
            return XCTFail("expected a snapshot")
        }
        XCTAssertEqual(seq, 35)
        XCTAssertTrue(snapshot.agents.isEmpty)

        guard case .success(.error(let errorSeq, let message)) = decode(#"{"version":1,"type":"error","seq":11,"message":"boom"}"#) else {
            return XCTFail("expected an error envelope")
        }
        XCTAssertEqual(errorSeq, 11)
        XCTAssertEqual(message, "boom")
    }

    func testLogMessagesCarryReasonTypeAndSeq() throws {
        XCTAssertEqual(try XCTUnwrap(reason(decode(Self.zeroAgentEnvelope, after: 35))).logMessage,
                       "dropped envelope: reason=stale-seq type=snapshot seq=35 lastSeq=35")
        XCTAssertEqual(try XCTUnwrap(reason(decode(Self.snapshotWithoutMenubar))).logMessage,
                       "dropped envelope: reason=undecodable-body type=snapshot seq=7 path=data.menubar detail=missing key")
    }
}
