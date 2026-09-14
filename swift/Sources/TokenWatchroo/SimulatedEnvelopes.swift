import Foundation

/// Debug only, for testing dropped envelopes without a broken library. When `TW_DEBUG_ENVELOPES_FILE` holds an absolute
/// path, every refresh passes each non-empty line of that file through `Core.handle` as an envelope. The file is read on
/// every refresh and never changed.
enum SimulatedEnvelopes {

    static let envVar = "TW_DEBUG_ENVELOPES_FILE"

    static func file(environment: [String: String]) -> URL? {
        guard let value = environment[envVar], !value.isEmpty, value.hasPrefix("/") else { return nil }
        return URL(fileURLWithPath: value)
    }

    /// Split on the newline byte without decoding, so a line that is not UTF-8 is passed on as it is.
    static func lines(in file: URL) -> [Data] {
        guard let bytes = try? Data(contentsOf: file) else { return [] }
        return bytes.split(separator: 0x0A, omittingEmptySubsequences: false)
            .map { $0.last == 0x0D ? Data($0.dropLast()) : Data($0) }
            .filter { !$0.isEmpty }
    }
}
