// swift-tools-version:5.9
import PackageDescription
import Foundation

// JsonUI comes from GitHub. Set JSONUI_PATH to a local checkout to build
// against it (used by CI on Linux and while developing both repos together).
let jsonUI: Package.Dependency = ProcessInfo.processInfo.environment["JSONUI_PATH"].map { .package(path: $0) }
    ?? .package(url: "https://github.com/bclnet/JsonUI", branch: "master")

// The manifest stays at the repository root so the package can be added by URL;
// the Swift sources live in ios/ next to the Android project in android/.
let package = Package(
    name: "QRX",
    platforms: [
        .iOS(.v15), .macOS(.v12)
    ],
    products: [
        // Glyph payload/document parsing, lookup, and the BLUE/1.0 protocol. Platform independent.
        .library(name: "QRXCore", targets: ["QRXCore"]),
    ],
    dependencies: [
        jsonUI,
    ],
    targets: [
        .target(
            name: "QRXCore",
            dependencies: [.product(name: "JsonUICore", package: "JsonUI")],
            path: "ios/Sources/QRXCore"),
        .testTarget(
            name: "QRXCoreTests",
            dependencies: ["QRXCore"],
            path: "ios/Tests/QRXCoreTests"),
    ]
)
