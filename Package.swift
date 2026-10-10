// swift-tools-version: 6.0
// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import PackageDescription

let package = Package(
    name: "CrateCore",
    platforms: [.macOS(.v12)],
    products: [
        .library(name: "CrateCore", targets: ["CrateCore"]),
        .executable(name: "Crate", targets: ["Crate"]),
        .executable(name: "crate-scan", targets: ["crate-scan"]),
    ],
    targets: [
        .target(
            name: "CrateCore",
            linkerSettings: [.linkedFramework("AVFoundation")]
        ),
        // The app: a SwiftUI executable, wrapped into Crate.app by
        // scripts/bundle.sh.
        .executableTarget(name: "Crate", dependencies: ["CrateCore"]),
        // Developer tool, see Sources/crate-scan/main.swift.
        .executableTarget(name: "crate-scan", dependencies: ["CrateCore"]),
        .testTarget(
            name: "CrateCoreTests",
            dependencies: ["CrateCore"]
        ),
    ]
)
