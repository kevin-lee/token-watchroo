import XCTest
@testable import TokenWatchroo

@MainActor
final class FormattingTests: XCTestCase {

    private static let enUS = Locale(identifier: "en_US")

    func testAUsdSpendSummaryMatchesTheClaudeUsagePage() throws {
        let spend = Spend(currency: "USD", spent: try XCTUnwrap(Decimal(string: "0.05")),
                          limit: try XCTUnwrap(Decimal(string: "200.00")), usedPercent: 0.025, resetsAt: 1790812800)
        XCTAssertEqual(Formatting.spendSummary(spend, locale: Self.enUS), "$0.05 of $200.00 spent")
    }

    func testAUsdSpendSummaryUsesTheNarrowSymbolInEnAu() throws {
        let spend = Spend(currency: "USD", spent: try XCTUnwrap(Decimal(string: "0.05")),
                          limit: try XCTUnwrap(Decimal(string: "200.00")), usedPercent: 0.025, resetsAt: 1790812800)
        XCTAssertEqual(Formatting.spendSummary(spend, locale: Locale(identifier: "en_AU")), "$0.05 of $200.00 spent")
    }

    func testACreditsSpendSummaryNamesTheCredits() throws {
        let spend = Spend(currency: "credits", spent: try XCTUnwrap(Decimal(string: "8000")),
                          limit: try XCTUnwrap(Decimal(string: "25000")), usedPercent: 32, resetsAt: nil)
        XCTAssertEqual(Formatting.spendSummary(spend, locale: Self.enUS), "8,000 of 25,000 credits spent")
    }

    func testResetsOnShowsTheLocalDate() throws {
        let brisbane = try XCTUnwrap(TimeZone(identifier: "Australia/Brisbane"))
        XCTAssertEqual(Formatting.resetsOn(epoch: 1790812800, locale: Self.enUS, timeZone: brisbane), "resets Thu, Oct 1")
    }

    func testObservedAtShowsTheLocalDateAndTime() throws {
        let brisbane = try XCTUnwrap(TimeZone(identifier: "Australia/Brisbane"))
        XCTAssertEqual(Formatting.observedAt(epoch: 1790565092, locale: Self.enUS, timeZone: brisbane), "Sep 28 at 13:11")
    }

    func testLocalLogNoteShowsOnlyTheKnownParts() throws {
        let brisbane = try XCTUnwrap(TimeZone(identifier: "Australia/Brisbane"))
        XCTAssertEqual(Formatting.localLogNote(observedAt: 1790565092, error: "Network error: curl 6", locale: Self.enUS,
                                               timeZone: brisbane),
                       "from local log (Sep 28 at 13:11) · Network error: curl 6")
        XCTAssertEqual(Formatting.localLogNote(observedAt: nil, error: "Network error: curl 6", locale: Self.enUS,
                                               timeZone: brisbane),
                       "from local log · Network error: curl 6")
        XCTAssertEqual(Formatting.localLogNote(observedAt: nil, error: nil, locale: Self.enUS, timeZone: brisbane),
                       "from local log")
    }
}
