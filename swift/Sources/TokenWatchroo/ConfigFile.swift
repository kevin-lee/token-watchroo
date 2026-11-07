import Foundation

/// `<stateDir>/config.json`, which Swift owns (#8). The library never reads or writes it: it only gets the JSON through
/// `tw_start` and `tw_set_config`. `load` has no side effects. The caller moves an undecodable file aside.
enum ConfigFile {

    static let fileName = "config.json"
    static let asideFileName = "config.json.bad"

    /// What `load` found.
    enum Loaded {
        /// No file: the first run. The defaults are used and nothing is written until the first change.
        case missing(Config)
        case loaded(Config)
        /// The file exists but does not decode. The defaults are used.
        case undecodable(Config, String)
        /// The file exists but cannot be read, for example because of its permissions. The defaults are used.
        case unreadable(Config, String)

        var config: Config {
            switch self {
            case .missing(let config), .loaded(let config), .undecodable(let config, _), .unreadable(let config, _):
                return config
            }
        }
    }

    static func url(in stateDirectory: URL) -> URL {
        stateDirectory.appendingPathComponent(fileName, isDirectory: false)
    }

    /// `stateDir` in the file is ignored: it is always the real state directory, so a moved folder still works.
    static func load(from url: URL, stateDir: String) -> Loaded {
        let defaults = Config.defaults(stateDir: stateDir)
        guard FileManager.default.fileExists(atPath: url.path) else { return .missing(defaults) }
        let data: Data
        do {
            data = try Data(contentsOf: url)
        } catch {
            return .unreadable(defaults, error.localizedDescription)
        }
        do {
            var config = try JSONDecoder().decode(Config.self, from: data)
            config.stateDir = stateDir
            return .loaded(config)
        } catch {
            return .undecodable(defaults, String(describing: error))
        }
    }

    /// Pretty printed with sorted keys, so a person can read the file and a diff of it.
    static func encode(_ config: Config) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        return try encoder.encode(config)
    }

    static func write(_ config: Config, to url: URL) throws {
        try encode(config).write(to: url, options: .atomic)
    }

    /// Moves the file to `config.json.bad` next to it, replacing an older one, and returns the new location.
    @discardableResult
    static func moveAside(_ url: URL) throws -> URL {
        let aside = url.deletingLastPathComponent().appendingPathComponent(asideFileName, isDirectory: false)
        if FileManager.default.fileExists(atPath: aside.path) {
            try FileManager.default.removeItem(at: aside)
        }
        try FileManager.default.moveItem(at: url, to: aside)
        return aside
    }
}
