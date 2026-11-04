// swift-tools-version:5.10
import PackageDescription

let package = Package(
    name: "Margin",
    platforms: [.macOS(.v14)],
    targets: [
        .target(name: "GitKit"),
        .executableTarget(name: "Margin", dependencies: ["GitKit"]),
        // No XCTest/Testing with Command Line Tools only, so checks are an assert-based executable.
        .executableTarget(name: "selfcheck", dependencies: ["GitKit"]),
    ]
)
