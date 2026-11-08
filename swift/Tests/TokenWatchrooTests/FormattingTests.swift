import XCTest
@testable import TokenWatchroo

@MainActor
final class FormattingTests: XCTestCase {

    private static let enUS = Locale(identifier: "en_US")

    func testAUsdSpendSummaryMatchesTheClaudeUsagePage() throws {
        let spend = Spend(currency: "USD", spent: try XCTUnwrap(Decimal(string: "0.05")),
                          limit: try XCTUnwrap(Decimal(string: "200.00")), usedPercent: 0.025, resetsAt: 1790812800)
        XCTAssertEqual(Formatting.spendSummary(spend, locale: Self.enUS, strings: TestStrings.en), "$0.05 of $200.00 spent")
    }

    func testAUsdSpendSummaryUsesTheNarrowSymbolInEnAu() throws {
        let spend = Spend(currency: "USD", spent: try XCTUnwrap(Decimal(string: "0.05")),
                          limit: try XCTUnwrap(Decimal(string: "200.00")), usedPercent: 0.025, resetsAt: 1790812800)
        XCTAssertEqual(Formatting.spendSummary(spend, locale: Locale(identifier: "en_AU"), strings: TestStrings.en), "$0.05 of $200.00 spent")
    }

    func testACreditsSpendSummaryNamesTheCredits() throws {
        let spend = Spend(currency: "credits", spent: try XCTUnwrap(Decimal(string: "8000")),
                          limit: try XCTUnwrap(Decimal(string: "25000")), usedPercent: 32, resetsAt: nil)
        XCTAssertEqual(Formatting.spendSummary(spend, locale: Self.enUS, strings: TestStrings.en), "8,000 of 25,000 credits spent")
    }

    func testResetsOnShowsTheLocalDate() throws {
        let brisbane = try XCTUnwrap(TimeZone(identifier: "Australia/Brisbane"))
        XCTAssertEqual(Formatting.resetsOn(epoch: 1790812800, locale: Self.enUS, timeZone: brisbane, strings: TestStrings.en), "resets Thu, Oct 1")
    }

    func testObservedAtShowsTheLocalDateAndTime() throws {
        let brisbane = try XCTUnwrap(TimeZone(identifier: "Australia/Brisbane"))
        XCTAssertEqual(Formatting.observedAt(epoch: 1790565092, locale: Self.enUS, timeZone: brisbane), "Sep 28 at 13:11")
    }

    func testLocalLogNoteShowsOnlyTheKnownParts() throws {
        let brisbane = try XCTUnwrap(TimeZone(identifier: "Australia/Brisbane"))
        XCTAssertEqual(Formatting.localLogNote(observedAt: 1790565092, error: "Network error: curl 6", locale: Self.enUS,
                                               timeZone: brisbane, strings: TestStrings.en),
                       "from local log (Sep 28 at 13:11) · Network error: curl 6")
        XCTAssertEqual(Formatting.localLogNote(observedAt: nil, error: "Network error: curl 6", locale: Self.enUS,
                                               timeZone: brisbane, strings: TestStrings.en),
                       "from local log · Network error: curl 6")
        XCTAssertEqual(Formatting.localLogNote(observedAt: nil, error: nil, locale: Self.enUS, timeZone: brisbane,
                                               strings: TestStrings.en),
                       "from local log")
    }

    /// The short labels and the header in both languages (#10).
    func testShortLabelsInBothLanguages() {
        let now = Date(timeIntervalSince1970: 1_000_000)
        XCTAssertEqual(Formatting.shortDuration(seconds: 4320, strings: TestStrings.en), "1 h 12 m")
        XCTAssertEqual(Formatting.shortDuration(seconds: 4320, strings: TestStrings.ko), "1시간 12분")
        XCTAssertEqual(Formatting.shortDuration(seconds: 183_600, strings: TestStrings.ko), "2일 3시간")
        XCTAssertEqual(Formatting.shortDuration(seconds: 30, strings: TestStrings.ko), "1분 미만")
        XCTAssertEqual(Formatting.updatedAgo(since: now.addingTimeInterval(-3540), now: now, strings: TestStrings.ko),
                       "59분 전 업데이트")
        XCTAssertEqual(Formatting.updatedAgo(since: now.addingTimeInterval(-30), now: now, strings: TestStrings.en),
                       "Updated 30 s ago")
        XCTAssertEqual(Formatting.resetsIn(epoch: 1_000_000 + 15_120, now: now, strings: TestStrings.ko), "4시간 12분 후 초기화")
        XCTAssertEqual(Formatting.resetsIn(epoch: 1_000_000 + 15_120, now: now, strings: TestStrings.en),
                       "resets in 4 h 12 m")
        XCTAssertEqual(Formatting.shortCountdown(toEpoch: 999_000, now: now, strings: TestStrings.ko), "초기화 중...")
    }
}
