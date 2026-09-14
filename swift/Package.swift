// swift-tools-version: 6.0
import PackageDescription

// The Scala Native static library must be staged first: `sbt stageNativeLib` copies it to lib/libtokenwatchroo.a.
// `swift build` and `swift test` must run from this directory so the relative archive path resolves.
let package = Package(
    name: "TokenWatchroo",
    platforms: [.macOS(.v14)],
    targets: [
        .target(name: "CTokenWatchroo", path: "Sources/CTokenWatchroo"),
        .executableTarget(
            name: "TokenWatchroo",
            dependencies: ["CTokenWatchroo"],
            path: "Sources/TokenWatchroo",
            linkerSettings: [
                // Explicit linker input, not -l: a stray dylib could otherwise be picked instead of the archive.
                .unsafeFlags(["-Xlinker", "lib/libtokenwatchroo.a"]),
                .linkedLibrary("c++"),
                .linkedLibrary("curl"),
                .linkedLibrary("pthread"),
                .linkedLibrary("dl"),
                .linkedFramework("AppKit"),
                .linkedFramework("UserNotifications"),
                .linkedFramework("ServiceManagement"),
            ]
        ),
        .testTarget(name: "TokenWatchrooTests", dependencies: ["TokenWatchroo"], path: "Tests/TokenWatchrooTests"),
    ]
)
