// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation
import Testing
@testable import CrateCore

@Suite struct FavoritesTests {
    let a = makeTrack("a", album: "One")
    let b = makeTrack("b", album: "One")
    let c = makeTrack("c", album: "Two", folder: "/music/two")

    @Test func toggleAddsThenRemoves() {
        var favorites = Favorites()
        #expect(favorites.isEmpty)
        let added = favorites.toggle(a)
        #expect(added)
        #expect(favorites.contains(a))
        #expect(!favorites.contains(b))
        #expect(favorites.count == 1)
        let addedAgain = favorites.toggle(a)
        #expect(!addedAgain)
        #expect(!favorites.contains(a))
        #expect(favorites.isEmpty)
    }

    @Test func setMarksAndUnmarksSeveralAtOnce() {
        var favorites = Favorites()
        favorites.set(true, for: [a, b])
        #expect(favorites.containsAll([a, b]))
        #expect(!favorites.containsAll([a, b, c]))
        #expect(!Favorites().containsAll([]))
        favorites.set(false, for: [a])
        #expect(favorites.ids == [b.id])
    }

    @Test func filterKeepsOrderAndSkipsUnknown() {
        var favorites = Favorites(ids: ["/elsewhere/gone.mp3"])
        favorites.set(true, for: [c, a])
        #expect(favorites.filter([a, b, c]) == [a, c])
        #expect(favorites.count == 3)
    }

    @Test func removingAFolderDropsOnlyItsIds() {
        var favorites = Favorites()
        favorites.set(true, for: [a, c, makeTrack("d", folder: "/music2")])
        favorites.removeAll(under: URL(fileURLWithPath: "/music/two"))
        #expect(favorites.ids == [a.id, "/music2/d.mp3"])
        favorites.removeAll(under: URL(fileURLWithPath: "/music/"))
        #expect(favorites.ids == ["/music2/d.mp3"])
    }

    @Test func storeRoundTripsAndLoadsNilWhenMissing() throws {
        let dir = try TemporaryDirectory()
        let store = JSONFavoritesStore(fileURL: dir.url.appendingPathComponent("deep/favorites.json"))
        #expect(try store.load() == nil)

        var favorites = Favorites()
        favorites.set(true, for: [b, a, c])
        try store.save(favorites)
        #expect(try store.load() == favorites)

        // The ids are sorted, so the same set gives the same bytes.
        let text = try String(contentsOf: store.fileURL, encoding: .utf8)
        #expect(text.contains("\"version\" : 1"))
        #expect(text.range(of: a.id)!.lowerBound < text.range(of: b.id)!.lowerBound)
        #expect(text.range(of: b.id)!.lowerBound < text.range(of: c.id)!.lowerBound)
    }

    @Test func defaultFileSitsNextToTheLibraryIndex() throws {
        let library = try JSONLibraryStore.defaultFileURL(appName: "CrateTests")
        let favorites = try JSONFavoritesStore.defaultFileURL(appName: "CrateTests")
        #expect(favorites.deletingLastPathComponent() == library.deletingLastPathComponent())
        #expect(favorites.lastPathComponent == "favorites.json")
    }
}
