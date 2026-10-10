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
        let snapshot = LibrarySnapshot(folders: [dir.url], tracks: [track])

        try store.save(snapshot)
        let loaded = try store.load()
        #expect(loaded == snapshot)
        #expect(Library.unchanged(loaded!.tracks[0], ScannedFile(url: track.url, fileSize: 1, modified: track.modified)))
    }
}
