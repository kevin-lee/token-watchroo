import CTokenWatchroo
import Foundation

/// The only place that calls the library. Every `tw_*` call happens on the main thread, the callback copies its
/// string and hops to the main queue, and envelopes are applied in `seq` order.
@MainActor
final class Core {

    static let shared = Core()

    var snapshotHandler: ((Snapshot) -> Void)?
    var alertHandler: ((AlertPayload) -> Void)?
    var errorHandler: ((String) -> Void)?

    private(set) var latestSnapshot: Snapshot?
    private(set) var latestSnapshotAt: Date?

    private var started = false
    private var lastSeq: Int64 = -1
    private let decoder = JSONDecoder()

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
        _ = tw_refresh()
    }

    func shutdown() {
        precondition(Thread.isMainThread, "tw_shutdown must be called from the main thread")
        guard started else { return }
        started = false
        _ = tw_shutdown()
    }

    fileprivate func handle(json: String) {
        guard let data = json.data(using: .utf8) else { return }
        guard let head = try? decoder.decode(EnvelopeHead.self, from: data) else {
            errorHandler?("undecodable envelope: \(json.prefix(200))")
            return
        }
        guard head.seq > lastSeq else { return }
        lastSeq = head.seq
        switch head.type {
        case "snapshot":
            if let body = try? decoder.decode(SnapshotEnvelope.self, from: data) {
                latestSnapshot = body.data
                latestSnapshotAt = Date()
                snapshotHandler?(body.data)
            } else {
                errorHandler?("undecodable snapshot envelope")
            }
        case "alert":
            if let body = try? decoder.decode(AlertEnvelope.self, from: data) {
                alertHandler?(body.data)
            } else {
                errorHandler?("undecodable alert envelope")
            }
        case "error":
            if let body = try? decoder.decode(ErrorEnvelope.self, from: data) {
                errorHandler?(body.message)
            }
        default:
            errorHandler?("unknown envelope type \(head.type)")
        }
    }
}

/// Runs on a library worker thread: copy the string, then leave immediately. Never calls back into the library.
/// `Core` is a main-actor singleton, so the context pointer is not needed to find it.
private let coreCallback: tw_callback = { cString, _ in
    guard let cString else { return }
    let json = String(cString: cString)
    DispatchQueue.main.async {
        MainActor.assumeIsolated {
            Core.shared.handle(json: json)
        }
    }
}
