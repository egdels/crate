// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation
import Testing
@testable import CrateCore

@Suite struct LibraryScannerTests {
    @Test func findsAudioFilesRecursivelyAndSkipsTheRest() throws {
        let dir = try TemporaryDirectory()
        let a = try dir.file("a.mp3")
        let b = try dir.file("sub/deeper/b.FLAC")
        try dir.file("notes.txt")
        try dir.file(".hidden.mp3")
        try dir.file("Thing.app/Contents/inside.mp3")
        try FileManager.default.createDirectory(at: dir.url.appendingPathComponent("folder.mp3"), withIntermediateDirectories: true)

        let files = try LibraryScanner().scan(dir.url)
        #expect(files.map(\.url) == [a, b].sorted { $0.path < $1.path })
        #expect(files.allSatisfy { $0.fileSize == 1 })
    }

    @Test func customExtensionsAreLowercased() throws {
        let dir = try TemporaryDirectory()
        let file = try dir.file("x.OGG")
        let files = try LibraryScanner(extensions: ["Ogg"]).scan(dir.url)
        #expect(files.map(\.url) == [file])
    }

    @Test func missingFolderThrows() {
        let missing = URL(fileURLWithPath: "/nonexistent/\(UUID().uuidString)")
        #expect(throws: (any Error).self) { try LibraryScanner().scan(missing) }
    }
}
