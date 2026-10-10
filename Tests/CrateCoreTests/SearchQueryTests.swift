// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Testing
@testable import CrateCore

@Suite struct SearchQueryTests {
    let tracks = [
        makeTrack("Nobody Else But You", artist: "Ada Lark", album: "Evening Songs"),
        makeTrack("Morning Light", artist: "Ada Lark", album: "Night Letters"),
        makeTrack("Señorita", artist: "Río Trio", album: "Costa"),
    ]

    @Test func emptyQueryMatchesEverything() {
        let query = SearchQuery("   ")
        #expect(query.isEmpty)
        #expect(tracks.allSatisfy(query.matches))
    }

    @Test func everyWordMustMatchSomeField() {
        #expect(SearchQuery("ada nobody").matches(tracks[0]))
        #expect(!SearchQuery("ada nobody").matches(tracks[1]))
        #expect(SearchQuery("night").matches(tracks[1]))
    }

    @Test func ignoresCaseAndDiacritics() {
        #expect(SearchQuery("senorita").matches(tracks[2]))
        #expect(SearchQuery("RIO TRIO").matches(tracks[2]))
    }

    @Test func albumMatchesThroughTitleArtistOrTrack() {
        let albums = Album.group(tracks)
        let spell = albums.first { $0.title == "Night Letters" }!
        let costa = albums.first { $0.title == "Costa" }!
        #expect(SearchQuery("morning").matches(spell))
        #expect(SearchQuery("lark night").matches(spell))
        #expect(!SearchQuery("morning").matches(costa))
        #expect(SearchQuery("trio").matches(costa))
    }
}
