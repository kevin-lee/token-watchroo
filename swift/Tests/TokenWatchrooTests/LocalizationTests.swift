import AppKit
import XCTest
@testable import TokenWatchroo

/// The shell's strings (#10): keys and placeholders in both languages, the language choice, the number rules, the FFI
/// config, the words shared with the core's bundles, and the Korean widths measured on 2026-11-09.
@MainActor
final class LocalizationTests: XCTestCase {

    private static let languages: [AppStrings] = [TestStrings.en, TestStrings.ko]

    private static func placeholders(_ text: String) -> Set<String> {
        let regex = try? NSRegularExpression(pattern: "\\{([A-Za-z]+)\\}")
        let range = NSRange(text.startIndex..., in: text)
        return Set((regex?.matches(in: text, range: range) ?? []).compactMap { match in
            Range(match.range(at: 1), in: text).map { String(text[$0]) }
        })
    }

    func testEveryKeyIsInBothTables() {
        for strings in Self.languages {
            for key in StringKey.allCases {
                XCTAssertNotNil(strings.table[key.rawValue], "\(strings.language) \(key.rawValue)")
            }
        }
    }

    func testNoTableHasAnotherKey() {
        let keys = Set(StringKey.allCases.map(\.rawValue))
        for strings in Self.languages {
            XCTAssertEqual(Set(strings.table.keys).subtracting(keys), [], "\(strings.language)")
        }
    }

    func testThePlaceholdersMatchTheKeys() {
        for strings in Self.languages {
            for key in StringKey.allCases {
                XCTAssertEqual(Self.placeholders(strings.table[key.rawValue] ?? ""), Set(key.params),
                               "\(strings.language) \(key.rawValue)")
            }
        }
    }

    func testTextFillsInOnePassAndKeepsUnknownBraces() {
        XCTAssertEqual(TestStrings.en.text(.windowModel, ["model": "{model}"]), "Weekly ({model})")
        XCTAssertEqual(TestStrings.ko.text(.windowModel, ["model": "Fable"]), "주간 (Fable)")
        XCTAssertEqual(AppStrings(language: .en, table: [:]).text(.menuQuit), "menu.quit")
    }

    func testTheLanguageFollowsTheFirstPreferredLanguageTheAppHas() {
        XCTAssertEqual(AppLanguage.choose(preferences: ["ko-KR"]), .ko)
        XCTAssertEqual(AppLanguage.choose(preferences: ["ja-JP", "ko-KR"]), .ko)
        XCTAssertEqual(AppLanguage.choose(preferences: ["fr-FR"]), .en)
        XCTAssertEqual(AppLanguage.choose(preferences: ["en-AU", "ko-AU"]), .en)
        XCTAssertEqual(AppLanguage.choose(preferences: []), .en)
    }

    func testTheNumberRulesFollowTheRegion() {
        XCTAssertEqual(NumberFormatPayload(locale: Locale(identifier: "en_AU")),
                       NumberFormatPayload(decimalSeparator: ".", groupingSeparator: ",", currencySymbol: "before"))
        XCTAssertEqual(NumberFormatPayload(locale: Locale(identifier: "de_DE")),
                       NumberFormatPayload(decimalSeparator: ",", groupingSeparator: ".", currencySymbol: "after-spaced"))
        XCTAssertEqual(NumberFormatPayload(locale: Locale(identifier: "ko_KR")).currencySymbol, "before")
    }

    func testTheLibraryGetsLocaleAndNumberFormatButConfigJsonDoesNot() throws {
        var config = Config.defaults(stateDir: "/state")
        config.agents = [AgentConfig.defaults(.codex)]
        let format = NumberFormatPayload(decimalSeparator: ",", groupingSeparator: ".", currencySymbol: "after-spaced")
        let json = try XCTUnwrap(Core.payload(config: config, language: .ko, numberFormat: format))
        let object = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any])
        XCTAssertEqual(object["locale"] as? String, "ko")
        XCTAssertEqual((object["numberFormat"] as? [String: String])?["currencySymbol"], "after-spaced")
        XCTAssertEqual(object["stateDir"] as? String, "/state")
        XCTAssertEqual((object["agents"] as? [Any])?.count, 1)
        let file = try XCTUnwrap(String(data: try ConfigFile.encode(config), encoding: .utf8))
        XCTAssertFalse(file.contains("locale"))
        XCTAssertFalse(file.contains("numberFormat"))
    }

    /// The core's bundle for a language, read with a small `key = value` reader.
    private static func coreBundle(_ language: AppLanguage) throws -> [String: String] {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("modules/token-watchroo-core/src/main/resources/tokenwatchroo/i18n")
            .appendingPathComponent("messages_\(language.rawValue).properties")
        let text = try String(contentsOf: url, encoding: .utf8)
        var table: [String: String] = [:]
        for line in text.split(separator: "\n") where !line.hasPrefix("#") {
            guard let equals = line.firstIndex(of: "=") else { continue }
            table[line[..<equals].trimmingCharacters(in: .whitespaces)] =
                line[line.index(after: equals)...].trimmingCharacters(in: .whitespaces)
        }
        return table
    }

    func testTheSharedWordsMatchTheCoreBundles() throws {
        for strings in Self.languages {
            let core = try Self.coreBundle(strings.language)
            let session = try XCTUnwrap(core["window.limit.session"])
            XCTAssertEqual(strings.text(.windowSession),
                           strings.language == .en ? session.prefix(1).uppercased() + session.dropFirst() : session)
            XCTAssertEqual(strings.text(.sheetApiHint), try XCTUnwrap(core["error.apiNotSupported"]) + ".")
            let configDir = try XCTUnwrap(core["credentials.claude.configDirNoSignIn"])
            XCTAssertTrue(configDir.lowercased().contains(strings.text(.sheetConfigDir).lowercased()),
                          "\(strings.language): \(configDir)")
        }
    }

    private static func lines(_ text: String, size: CGFloat, width: CGFloat) -> Int {
        let font = NSFont.systemFont(ofSize: size)
        let rect = (text as NSString).boundingRect(with: NSSize(width: width, height: 1000),
                                                    options: [.usesLineFragmentOrigin], attributes: [.font: font])
        return Int((rect.height / font.boundingRectForFont.height).rounded())
    }

    private static func width(_ text: String, size: CGFloat, weight: NSFont.Weight = .regular) -> CGFloat {
        (text as NSString).size(withAttributes: [.font: NSFont.systemFont(ofSize: size, weight: weight)]).width
    }

    /// The unavailable card shows its text at 11 pt in 304 pt on 2 lines. The pills and the header must fit as wide
    /// as the English ones did.
    func testTheKoreanCardTextsFit() throws {
        let core = try Self.coreBundle(.ko)
        for key in core.keys where key.hasPrefix("credentials.") || key.hasPrefix("error.") {
            let text = try XCTUnwrap(core[key])
            XCTAssertLessThanOrEqual(Self.lines(text, size: 11, width: 304), 2, "\(key): \(text)")
        }
        let note = TestStrings.ko.text(.cardNoCurrentUsage) + " · "
            + Formatting.localLogNote(observedAt: 1790565092, error: "네트워크 오류: curl 6",
                                      locale: Locale(identifier: "ko_KR"), strings: TestStrings.ko)
        XCTAssertLessThanOrEqual(Self.lines(note, size: 11, width: 304), 2, note)
        for status in [AgentStatus.ok, .elevated, .warning, .critical, .exhausted, .unavailable] {
            let korean = Palette.statusPill(for: status, strings: TestStrings.ko).0
            let english = Palette.statusPill(for: status, strings: TestStrings.en).0
            XCTAssertLessThanOrEqual(Self.width(korean, size: 11, weight: .medium),
                                     max(Self.width(english, size: 11, weight: .medium), 42), korean)
        }
        XCTAssertLessThanOrEqual(Self.width(TestStrings.ko.text(.headerUpdatedMinutes, ["n": "59"]), size: 11), 98)
    }
}
