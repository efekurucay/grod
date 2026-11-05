// swift-tools-version: 6.3
import PackageDescription

let package = Package(
    name: "grod",
    platforms: [.macOS(.v26)],
    products: [
        .library(name: "GrodCore", targets: ["GrodCore"]),
        .executable(name: "GrodBar", targets: ["GrodBar"])
    ],
    targets: [
        .target(name: "GrodCore"),
        .executableTarget(name: "GrodBar", dependencies: ["GrodCore"]),
        .testTarget(name: "GrodCoreTests", dependencies: ["GrodCore"])
    ]
)
