// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation
import Testing
@testable import CrateCore

@Suite struct BookmarksTests {
    private func chapter(_ name: String, folder: String = "/books/one", duration: TimeInterval = 600) -> Track {
        Track(
            url: URL(fileURLWithPath: "\(folder)/\(name).mp3"), fileSize: 1, modified: Date(timeIntervalSince1970: 0),
            title: name, genre: "Hörbuch", duration: duration)
    }

    private let day = Date(timeIntervalSince1970: 1_800_000_000)

    @Test func onlySpokenOrLongTracksGetAMark() {
        var bookmarks = Bookmarks()
        let song = makeTrack("song")
        bookmarks.set(song, at: 60)
        #expect(bookmarks.count == 0)
        #expect(bookmarks.position(for: song) == nil)
        let one = chapter("01")
        bookmarks.set(one, at: 60)
        #expect(bookmarks.position(for: one) == 60)
    }

    @Test func theVeryStartAndTheEndAreNoPlaceToResume() {
        var bookmarks = Bookmarks()
        let one = chapter("01")
        bookmarks.set(one, at: 300)
        bookmarks.set(one, at: 3)
        #expect(bookmarks.position(for: one) == nil)
        #expect(bookmarks.count == 0)
        bookmarks.set(one, at: 595)
        #expect(bookmarks.position(for: one) == nil)
        bookmarks.set(one, at: 300)
        bookmarks.finish(one)
        #expect(bookmarks.position(for: one) == nil)
        #expect(bookmarks.count == 0)
    }

    @Test func theAlbumResumesAtTheFileHeardLast() {
        var bookmarks = Bookmarks()
        let tracks = [chapter("01"), chapter("02"), chapter("03")]
        #expect(bookmarks.resumeIndex(in: tracks) == nil)
        bookmarks.set(tracks[2], at: 100, now: day)
        bookmarks.set(tracks[1], at: 200, now: day.addingTimeInterval(60))
        #expect(bookmarks.resumeIndex(in: tracks) == 1)
        // Stopped just before the end of a file: the next one.
        bookmarks.set(tracks[1], at: 595, now: day.addingTimeInterval(120))
        #expect(bookmarks.resumeIndex(in: tracks) == 2)
    }

    @Test func theOldestMarksGoAndAFolderCanBePruned() {
        var bookmarks = Bookmarks()
        for i in 0..<(Bookmarks.limit + 5) {
            bookmarks.set(chapter("\(i)"), at: 60, now: day.addingTimeInterval(Double(i)))
        }
        #expect(bookmarks.count == Bookmarks.limit)
        #expect(bookmarks.entries[chapter("0").id] == nil)
        #expect(bookmarks.entries[chapter("\(Bookmarks.limit + 4)").id] != nil)
        bookmarks.set(chapter("x", folder: "/books/two"), at: 60, now: day.addingTimeInterval(1e6))
        bookmarks.removeAll(under: URL(fileURLWithPath: "/books/one"))
        #expect(bookmarks.count == 1)
    }

    @Test func storeRoundTrip() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("crate-bookmarks-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: url) }
        let store = JSONBookmarksStore(fileURL: url)
        #expect(try store.load() == nil)
        var bookmarks = Bookmarks()
        bookmarks.set(chapter("01"), at: 123.5, now: day)
        try store.save(bookmarks)
        #expect(try store.load() == bookmarks)
    }
}
