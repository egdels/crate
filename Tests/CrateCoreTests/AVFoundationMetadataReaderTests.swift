// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation
import Testing
@testable import CrateCore

@Suite struct AVFoundationMetadataReaderTests {
    @Test func readsDurationOfAnUntaggedWav() async throws {
        let dir = try TemporaryDirectory()
        let file = try dir.wav("silence.wav", seconds: 1.5)
        let meta = try await AVFoundationMetadataReader().read(file)
        #expect(abs(meta.duration - 1.5) < 0.01)
        #expect(meta.title == nil)
        #expect(meta.trackNumber == nil)
    }

    @Test func libraryFallsBackToFileNameForUntaggedFiles() async throws {
        let dir = try TemporaryDirectory()
        let music = dir.url.appendingPathComponent("music")
        _ = try dir.wav("music/03 Untitled Take.wav", seconds: 0.5)
        let library = Library(
            store: JSONLibraryStore(fileURL: dir.url.appendingPathComponent("library.json")),
            metadataReader: AVFoundationMetadataReader())
        let summary = try await library.addFolder(music)
        #expect(summary == Library.RescanSummary(added: 1))
        let track = await library.tracks[0]
        #expect(track.title == "03 Untitled Take")
        #expect(abs(track.duration - 0.5) < 0.01)
    }

    @Test func unreadableFileThrows() async throws {
        let dir = try TemporaryDirectory()
        let junk = try dir.file("junk.mp3", contents: "not audio at all")
        await #expect(throws: (any Error).self) {
            try await AVFoundationMetadataReader().read(junk)
        }
    }

    @Test func yearParsing() {
        #expect(AVFoundationMetadataReader.year("1997") == 1997)
        #expect(AVFoundationMetadataReader.year("2003-04-05T00:00:00Z") == 2003)
        #expect(AVFoundationMetadataReader.year("abc") == nil)
        #expect(AVFoundationMetadataReader.year("12") == nil)
        // ID3 TDAT (day+month) and TIME must not pass as years.
        #expect(AVFoundationMetadataReader.year("0805") == nil)
        #expect(AVFoundationMetadataReader.year("1741") == nil)
    }

    @Test func genreParsing() {
        #expect(AVFoundationMetadataReader.genre(fromText: "Blues") == "Blues")
        #expect(AVFoundationMetadataReader.genre(fromText: "(17)") == "Rock")
        #expect(AVFoundationMetadataReader.genre(fromText: "0") == "Blues")
        #expect(AVFoundationMetadataReader.genre(fromText: "(999)") == "(999)")
        #expect(ID3v1Genres.name(index: 0) == "Blues")
        #expect(ID3v1Genres.name(index: 147) == "Synthpop")
        #expect(ID3v1Genres.name(index: 148) == nil)
        #expect(ID3v1Genres.name(index: -1) == nil)
    }
}
