import AppKit
import XCTest
@testable import TokenWatchroo

@MainActor
final class AboutTests: XCTestCase {

    func testTheFullVersionWins() {
        let info: [String: Any] = [About.versionKey: "0.1.2+5-0ee2f7d5", "CFBundleShortVersionString": "0.1.2"]
        XCTAssertEqual(About.version(infoDictionary: info), "0.1.2+5-0ee2f7d5")
    }

    func testTheShortVersionIsTheFallback() {
        XCTAssertEqual(About.version(infoDictionary: ["CFBundleShortVersionString": "0.1.2"]), "0.1.2")
    }

    func testAnEmptyFullVersionFallsBackToTheShortVersion() {
        let info: [String: Any] = [About.versionKey: "", "CFBundleShortVersionString": "0.1.2"]
        XCTAssertEqual(About.version(infoDictionary: info), "0.1.2")
    }

    func testNoVersionIsUnbundled() {
        XCTAssertEqual(About.version(infoDictionary: nil), "unbundled")
        XCTAssertEqual(About.version(infoDictionary: [:]), "unbundled")
    }

    func testArchitectureLabels() {
        XCTAssertEqual(About.architectureLabel(.arm64, translated: false), "arm64")
        XCTAssertEqual(About.architectureLabel(.arm64, translated: true), "arm64")
        XCTAssertEqual(About.architectureLabel(.x64, translated: false), "x64")
        XCTAssertEqual(About.architectureLabel(.x64, translated: true), "x64 (Rosetta)")
    }

    func testTheOptionsBlankTheBuildVersionAndCarryTheCredits() throws {
        let info: [String: Any] = [About.versionKey: "0.1.2+5-0ee2f7d5", "CFBundleShortVersionString": "0.1.2"]
        let options = About.options(infoDictionary: info, architecture: "x64 (Rosetta)", icon: nil)

        XCTAssertEqual(options[.applicationVersion] as? String, "0.1.2+5-0ee2f7d5")
        XCTAssertEqual(options[.version] as? String, "")
        XCTAssertNil(options[.applicationIcon])
        let credits = try XCTUnwrap(options[.credits] as? NSAttributedString)
        XCTAssertTrue(credits.string.contains("Architecture: x64 (Rosetta)"))
        XCTAssertTrue(credits.string.contains("github.com/kevin-lee/token-watchroo"))
        XCTAssertTrue(credits.string.contains("github.com/kevin-lee)"))

        var links: [String] = []
        credits.enumerateAttribute(.link, in: NSRange(location: 0, length: credits.length)) { value, _, _ in
            if let link = value as? String { links.append(link) }
        }
        XCTAssertEqual(links, [About.projectLink, About.authorLink])
    }

    func testTheOptionsCarryTheIconWhenGiven() {
        let icon = NSImage(size: NSSize(width: 1, height: 1))
        let options = About.options(infoDictionary: nil, architecture: "arm64", icon: icon)
        XCTAssertIdentical(options[.applicationIcon] as? NSImage, icon)
    }

    func testTheIconDrawsTheDarkImageOnlyInADarkAppearance() throws {
        let icon = About.icon(light: Self.solid(red: 1, blue: 0), dark: Self.solid(red: 0, blue: 1))

        let light = try Self.centre(of: icon, in: .aqua)
        XCTAssertGreaterThan(light.redComponent, 0.9)
        XCTAssertLessThan(light.blueComponent, 0.1)

        let dark = try Self.centre(of: icon, in: .darkAqua)
        XCTAssertLessThan(dark.redComponent, 0.1)
        XCTAssertGreaterThan(dark.blueComponent, 0.9)
    }

    func testTheBundledIconIsNilWithoutTheResources() {
        XCTAssertNil(About.bundledIcon(in: Bundle(for: AboutTests.self)))
    }

    private static let side: CGFloat = 4

    private static func solid(red: CGFloat, blue: CGFloat) -> NSImage {
        NSImage(size: NSSize(width: side, height: side), flipped: false) { rect in
            NSColor(srgbRed: red, green: 0, blue: blue, alpha: 1).setFill()
            rect.fill()
            return true
        }
    }

    /// Draws the image into a bitmap with the appearance as the drawing appearance and reads the centre pixel.
    private static func centre(of image: NSImage, in name: NSAppearance.Name) throws -> NSColor {
        let rep = try XCTUnwrap(NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(side), pixelsHigh: Int(side),
                                                 bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                                                 colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0))
        let appearance = try XCTUnwrap(NSAppearance(named: name))
        appearance.performAsCurrentDrawingAppearance {
            NSGraphicsContext.saveGraphicsState()
            NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
            image.draw(in: NSRect(x: 0, y: 0, width: side, height: side))
            NSGraphicsContext.restoreGraphicsState()
        }
        let colour = try XCTUnwrap(rep.colorAt(x: Int(side) / 2, y: Int(side) / 2))
        return try XCTUnwrap(colour.usingColorSpace(.sRGB))
    }
}
