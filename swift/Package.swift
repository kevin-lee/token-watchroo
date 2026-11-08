// swift-tools-version: 6.0
import PackageDescription

// The Scala Native static library must be staged first: `sbt stageNativeLib` copies it to lib/libtokenwatchroo.a.
// `swift build` and `swift test` must run from this directory so the relative archive path resolves.
let package = Package(
    name: "TokenWatchroo",
    platforms: [.macOS(.v14)],
    dependencies: [
        .package(url: "https://github.com/sparkle-project/Sparkle", exact: "2.10.1"),
    ],
    targets: [
        .target(name: "CTokenWatchroo", path: "Sources/CTokenWatchroo"),
        .executableTarget(
            name: "TokenWatchroo",
            dependencies: ["CTokenWatchroo", .product(name: "Sparkle", package: "Sparkle")],
            path: "Sources/TokenWatchroo",
            linkerSettings: [
                // Explicit linker input, not -l: a stray dylib could otherwise be picked instead of the archive.
                .unsafeFlags(["-Xlinker", "lib/libtokenwatchroo.a"]),
                // Sparkle.framework is in Contents/Frameworks of the bundle (scripts/bundle-app.sh).
                .unsafeFlags(["-Xlinker", "-rpath", "-Xlinker", "@executable_path/../Frameworks"]),
                .linkedLibrary("c++"),
                .linkedLibrary("curl"),
                .linkedLibrary("pthread"),
                .linkedLibrary("dl"),
                .linkedFramework("AppKit"),
                .linkedFramework("UserNotifications"),
                .linkedFramework("ServiceManagement"),
                // CFStringNormalize in the static library's mac_text.c (#91).
                .linkedFramework("CoreFoundation"),
            ]
        ),
        .testTarget(name: "TokenWatchrooTests", dependencies: ["TokenWatchroo"], path: "Tests/TokenWatchrooTests"),
    ]
)
