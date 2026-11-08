import Foundation
@testable import TokenWatchroo

/// The shell's strings from `swift/Resources` (#10), for tests in both languages.
enum TestStrings {
    static let resources = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .appendingPathComponent("Resources", isDirectory: true)

    static let en: AppStrings = load(.en)
    static let ko: AppStrings = load(.ko)

    private static func load(_ language: AppLanguage) -> AppStrings {
        guard let strings = AppStrings.load(language, directory: resources) else {
            fatalError("missing \(language.rawValue).lproj/Localizable.strings in \(resources.path)")
        }
        return strings
    }
}
