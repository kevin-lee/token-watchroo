import CTokenWatchroo
import Foundation

/// The only place that calls the library. Every `tw_*` call happens on the main thread, the callback copies its
/// bytes and hops to the main queue, and envelopes are applied in `seq` order. A dropped envelope is logged with its
/// reason through `Log.core`.
@MainActor
final class Core {

    static let shared = Core()

    var snapshotHandler: ((Snapshot) -> Void)?
    var alertHandler: ((AlertPayload) -> Void)?

    private(set) var latestSnapshot: Snapshot?
    private(set) var latestSnapshotAt: Date?

    private var started = false
    private var lastSeq: Int64 = -1
    private let simulatedEnvelopesFile = SimulatedEnvelopes.file(environment: ProcessInfo.processInfo.environment)

    private init() {}

    /// Returns the library's code: 0 ok, 1 bad config, 2 internal error, 3 wrong thread, or -1 when encoding failed.
    func start(config: Config) -> Int32 {
        precondition(Thread.isMainThread, "tw_start must be called from the main thread")
        guard !started else { return 0 }
        guard let data = try? JSONEncoder().encode(config), let json = String(data: data, encoding: .utf8) else {
            return -1
        }
        let initCode = ScalaNativeInit()
        guard initCode == 0 else { return initCode }
        let context = Unmanaged.passUnretained(self).toOpaque()
        let code = json.withCString { tw_start($0, coreCallback, context) }
        started = code == 0
        return code
    }

    func refresh() {
        precondition(Thread.isMainThread, "tw_refresh must be called from the main thread")
        guard started else { return }
        let code = tw_refresh()
        if code != 0 {
            Log.core.error("tw_refresh returned \(code)")
        }
        if let simulatedEnvelopesFile {
            SimulatedEnvelopes.lines(in: simulatedEnvelopesFile).forEach(handle)
        }
    }

    func shutdown() {
        precondition(Thread.isMainThread, "tw_shutdown must be called from the main thread")
        guard started else { return }
        started = false
        let code = tw_shutdown()
        if code != 0 {
            Log.core.error("tw_shutdown returned \(code)")
        }
    }

    /// Applies an envelope in `seq` order, or logs why it was dropped. Only an applied envelope advances `lastSeq`.
    func handle(_ data: Data) {
        switch EnvelopeDecoder.decode(data, after: lastSeq) {
        case .failure(let reason):
            Log.core.error(reason.logMessage)
        case .success(let envelope):
            lastSeq = envelope.seq
            switch envelope {
            case .snapshot(_, let snapshot):
                latestSnapshot = snapshot
                latestSnapshotAt = Date()
                snapshotHandler?(snapshot)
            case .alert(_, let alert):
                alertHandler?(alert)
            case .error(let seq, let message):
                Log.core.error("library error: seq=\(seq) message=\(message)")
            }
        }
    }
}

/// Runs on a library worker thread: copy the bytes, then leave immediately. Never calls back into the library.
/// `Core` is a main-actor singleton, so the context pointer is not needed to find it.
private let coreCallback: tw_callback = { cString, _ in
    guard let cString else { return }
    let data = Data(bytes: cString, count: strlen(cString))
    DispatchQueue.main.async {
        MainActor.assumeIsolated {
            Core.shared.handle(data)
        }
    }
}
