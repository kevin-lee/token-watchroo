import AppKit

/// The standard About panel (#77): the full sbt-dynver version, the build architecture, and the project and author
/// links. `CFBundleShortVersionString` and `CFBundleVersion` hold the version stripped to integers, because Apple allows
/// nothing else there and Sparkle (#71) compares `CFBundleVersion`, so `scripts/bundle-app.sh` writes the full version,
/// such as `0.1.2+5-0ee2f7d5` for a dev build, to its own key. The icon follows the macOS appearance, where the bundle
/// icon on macOS 26 follows only the "Icon & widget style" setting.
@MainActor
enum About {

    static let versionKey = "TokenWatchrooVersion"
    static let projectLink = "https://github.com/kevin-lee/token-watchroo"
    static let authorLink = "https://github.com/kevin-lee"
    /// The light and dark renditions of `design/AppIcon.icon`, exported by `scripts/generate-icons.sh`.
    static let lightIconName = "AboutIcon-light"
    static let darkIconName = "AboutIcon-dark"

    /// The architecture the executable was built for, with the labels the disk image names use.
    enum Architecture {
        case arm64, x64

        #if arch(arm64)
        static let current = Architecture.arm64
        #elseif arch(x86_64)
        static let current = Architecture.x64
        #else
        #error("unsupported architecture")
        #endif
    }

    /// The full version, else the bundle's short version, else "unbundled" as for `swift run`.
    static func version(infoDictionary: [String: Any]?) -> String {
        [versionKey, "CFBundleShortVersionString"]
            .lazy
            .compactMap { infoDictionary?[$0] as? String }
            .first { !$0.isEmpty } ?? "unbundled"
    }

    /// An arm64 executable never runs translated, so only x64 can say Rosetta.
    static func architectureLabel(_ architecture: Architecture, translated: Bool) -> String {
        switch architecture {
        case .arm64: "arm64"
        case .x64: translated ? "x64 (Rosetta)" : "x64"
        }
    }

    /// Whether this process runs under Rosetta. The key does not exist on Intel Macs, where the call fails.
    static func isTranslated() -> Bool {
        var translated: Int32 = 0
        var size = MemoryLayout<Int32>.size
        return sysctlbyname("sysctl.proc_translated", &translated, &size, nil, 0) == 0 && translated == 1
    }

    /// Three centred lines: the architecture, then the project and author links shown without `https://`. The label
    /// colour keeps the text readable in dark mode, where an attributed string without a colour is drawn black.
    static func credits(architecture: String) -> NSAttributedString {
        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = .center
        let base: [NSAttributedString.Key: Any] = [
            .font: NSFont.systemFont(ofSize: NSFont.smallSystemFontSize),
            .foregroundColor: NSColor.labelColor,
            .paragraphStyle: paragraph,
        ]
        let parts: [(text: String, link: String?)] = [
            ("Architecture: \(architecture)\nProject: ", nil),
            (displayed(projectLink), projectLink),
            ("\nAuthor: Kevin Lee (", nil),
            (displayed(authorLink), authorLink),
            (")", nil),
        ]
        let credits = NSMutableAttributedString()
        for part in parts {
            let attributes = part.link.map { link in base.merging([.link: link]) { _, new in new } } ?? base
            credits.append(NSAttributedString(string: part.text, attributes: attributes))
        }
        return credits
    }

    /// An image that draws `dark` in a dark appearance and `light` otherwise. AppKit calls the handler again for each
    /// appearance it draws the image in, so the panel's icon follows an appearance change even while it is open.
    static func icon(light: NSImage, dark: NSImage) -> NSImage {
        NSImage(size: light.size, flipped: false) { rect in
            let isDark = NSAppearance.currentDrawing().bestMatch(from: [.aqua, .darkAqua]) == .darkAqua
            (isDark ? dark : light).draw(in: rect)
            return true
        }
    }

    /// The appearance-following icon from the bundle's resources, or nil when either rendition is missing, as for
    /// `swift run` and the tests, so AppKit shows the bundle icon.
    static func bundledIcon(in bundle: Bundle) -> NSImage? {
        func load(_ name: String) -> NSImage? {
            bundle.url(forResource: name, withExtension: "png").flatMap(NSImage.init(contentsOf:))
        }
        guard let light = load(lightIconName), let dark = load(darkIconName) else { return nil }
        return icon(light: light, dark: dark)
    }

    /// The build version is an empty string, which hides the "(…)" that AppKit would otherwise fill from
    /// `CFBundleVersion`, so a dev build does not read "Version 0.1.2+5-0ee2f7d5 (0.1.2)". Without an icon AppKit shows
    /// the bundle icon.
    static func options(infoDictionary: [String: Any]?, architecture: String,
                        icon: NSImage?) -> [NSApplication.AboutPanelOptionKey: Any] {
        var options: [NSApplication.AboutPanelOptionKey: Any] = [
            .applicationVersion: version(infoDictionary: infoDictionary),
            .version: "",
            .credits: credits(architecture: architecture),
        ]
        options[.applicationIcon] = icon
        return options
    }

    /// The app is `LSUIElement`, so without the activation the panel can open behind the frontmost app.
    static func show() {
        NSApp.activate()
        NSApp.orderFrontStandardAboutPanel(options: options(
            infoDictionary: Bundle.main.infoDictionary,
            architecture: architectureLabel(.current, translated: isTranslated()),
            icon: bundledIcon(in: .main)
        ))
    }

    private static func displayed(_ link: String) -> String {
        link.replacing("https://", with: "")
    }
}
