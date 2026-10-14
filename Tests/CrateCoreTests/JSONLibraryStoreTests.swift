// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation
import Testing
@testable import CrateCore

@Suite struct JSONLibraryStoreTests {
    @Test func missingFileLoadsAsNil() throws {
        let dir = try TemporaryDirectory()
        let store = JSONLibraryStore(fileURL: dir.url.appendingPathComponent("nope/library.json"))
        #expect(try store.load() == nil)
    }

    @Test func roundTripsExactly() throws {
        let dir = try TemporaryDirectory()
        let store = JSONLibraryStore(fileURL: dir.url.appendingPathComponent("deep/library.json"))
        var track = makeTrack("song", artist: "Someone", album: "Thing", track: 3, year: 2020)
        track.modified = Date(timeIntervalSince1970: 1_700_000_000.123456)
        track.genre = "Rock"
        track.discTotal = 2
        track.composer = "Someone Else"
        track.compilation = true
        track.artistSort = "Someone"
        track.albumSort = "Thing"
        let snapshot = LibrarySnapshot(folders: [dir.url], tracks: [track])

        try store.save(snapshot)
        let loaded = try store.load()
        #expect(loaded == snapshot)
        #expect(Library.unchanged(loaded!.tracks[0], ScannedFile(url: track.url, fileSize: 1, modified: track.modified)))
    }

    /// An index from version 1 has no composer, compilation or sort
    /// fields; it still loads, with the flag off.
    @Test func olderIndexLoadsWithDefaults() throws {
        let dir = try TemporaryDirectory()
        let file = try dir.file("library.json", contents: """
        {"version": 1, "folders": ["file:///music/"], "tracks": [
          {"url": "file:///music/a.mp3", "fileSize": 1, "modified": 0, "title": "A", "artist": "X", "duration": 10}
        ]}
        """)
        let loaded = try JSONLibraryStore(fileURL: file).load()
        #expect(loaded?.version == 1)
        #expect(loaded?.tracks[0].title == "A")
        #expect(loaded?.tracks[0].compilation == false)
        #expect(loaded?.tracks[0].composer == nil)
    }
}
