import Foundation

/// An envelope that `Core` applies.
enum DecodedEnvelope {
    case snapshot(seq: Int64, Snapshot)
    case alert(seq: Int64, AlertPayload)
    case error(seq: Int64, message: String)

    var seq: Int64 {
        switch self {
        case .snapshot(let seq, _), .alert(let seq, _), .error(let seq, _): return seq
        }
    }
}

/// Why `Core` did not apply an envelope. Each case is one log line.
enum EnvelopeDropReason: Error, Equatable {
    case notUTF8(byteCount: Int)
    case undecodableHead(detail: String, prefix: String)
    case staleSeq(type: String, seq: Int64, lastSeq: Int64)
    case unknownType(type: String, seq: Int64)
    case undecodableBody(type: String, seq: Int64, path: String, detail: String)

    var logMessage: String {
        let reason: String
        switch self {
        case .notUTF8(let byteCount):
            reason = "not-utf8 bytes=\(byteCount)"
        case .undecodableHead(let detail, let prefix):
            reason = "undecodable-head detail=\(detail) prefix=\(prefix)"
        case .staleSeq(let type, let seq, let lastSeq):
            reason = "stale-seq type=\(type) seq=\(seq) lastSeq=\(lastSeq)"
        case .unknownType(let type, let seq):
            reason = "unknown-type type=\(type) seq=\(seq)"
        case .undecodableBody(let type, let seq, let path, let detail):
            reason = "undecodable-body type=\(type) seq=\(seq) path=\(path) detail=\(detail)"
        }
        return "dropped envelope: reason=\(reason)"
    }
}

/// Pure envelope decoding, so every drop reason is testable without the `Core` singleton.
enum EnvelopeDecoder {

    /// A local `JSONDecoder` is cheap and keeps the function free of shared state.
    static func decode(_ data: Data, after lastSeq: Int64) -> Result<DecodedEnvelope, EnvelopeDropReason> {
        guard let text = String(data: data, encoding: .utf8) else {
            return .failure(.notUTF8(byteCount: data.count))
        }
        let decoder = JSONDecoder()
        let head: EnvelopeHead
        do {
            head = try decoder.decode(EnvelopeHead.self, from: data)
        } catch {
            return .failure(.undecodableHead(detail: describe(error).detail, prefix: String(text.prefix(200))))
        }
        guard head.seq > lastSeq else {
            return .failure(.staleSeq(type: head.type, seq: head.seq, lastSeq: lastSeq))
        }
        do {
            switch head.type {
            case "snapshot":
                return .success(.snapshot(seq: head.seq, try decoder.decode(SnapshotEnvelope.self, from: data).data))
            case "alert":
                return .success(.alert(seq: head.seq, try decoder.decode(AlertEnvelope.self, from: data).data))
            case "error":
                return .success(.error(seq: head.seq, message: try decoder.decode(ErrorEnvelope.self, from: data).message))
            default:
                return .failure(.unknownType(type: head.type, seq: head.seq))
            }
        } catch {
            let described = describe(error)
            return .failure(.undecodableBody(type: head.type, seq: head.seq, path: described.path, detail: described.detail))
        }
    }

    /// The coding path and a short detail of a decoding failure, internal for tests.
    static func describe(_ error: Error) -> (path: String, detail: String) {
        guard let decodingError = error as? DecodingError else {
            return ("", String(describing: error))
        }
        switch decodingError {
        case .keyNotFound(let key, let context):
            return (format(context.codingPath + [key]), "missing key")
        case .valueNotFound(let type, let context):
            return (format(context.codingPath), "null value, expected \(type)")
        case .typeMismatch(let type, let context):
            return (format(context.codingPath), "type mismatch, expected \(type)")
        case .dataCorrupted(let context):
            return (format(context.codingPath), context.debugDescription)
        @unknown default:
            return ("", String(describing: error))
        }
    }

    /// `data.agents[0].status`: an index is attached to the previous segment, keys are joined with dots.
    private static func format(_ codingPath: [CodingKey]) -> String {
        let joined = codingPath.reduce("") { result, key in
            if let index = key.intValue {
                return "\(result)[\(index)]"
            }
            return result.isEmpty ? key.stringValue : "\(result).\(key.stringValue)"
        }
        return joined.isEmpty ? "(root)" : joined
    }
}
