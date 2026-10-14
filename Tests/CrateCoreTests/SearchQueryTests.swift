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

    @Test func composerAndGenreAreSearchedToo() {
        var track = makeTrack("Suite", artist: "Glenn Gould", album: "Goldberg")
        track.composer = "Johann Sebastian Bach"
        track.genre = "Classical"
        #expect(SearchQuery("bach").matches(track))
        #expect(SearchQuery("classical gould").matches(track))
        #expect(!SearchQuery("mozart").matches(track))
        let album = Album.group([track])[0]
        #expect(album.genre == "Classical")
        #expect(SearchQuery("classical").matches(album))
        #expect(SearchQuery("bach").matches(album), "through the track")
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

    @Test func indexFindsWhatTheQueryMatches() {
        var gould = makeTrack("Aria", artist: "Glenn Gould", album: "Goldberg", folder: "/fourth")
        gould.composer = "Bach"
        gould.genre = "Classical"
        let tricky = tracks + [
            makeTrack("Straße", artist: "AC/DC", album: "Ｗｉｄｅ", folder: "/other"),
            makeTrack("Ångström", artist: "Björk", album: "Œuvre", folder: "/third"),
            gould,
        ]
        let albums = Album.group(tricky)
        let index = SearchIndex(albums: albums)
        for text in ["", "ada", "morning", "lark night", "senorita", "RIO costa", "nothing", "ada nobody",
                     "strasse", "STRASSE", "ac/dc", "acdc", "wide", "ｗｉｄｅ", "angstrom", "bjork", "oeuvre", "œuvre",
                     "bach", "classical gould", "CLASSICAL"] {
            let query = SearchQuery(text)
            let result = index.search(query)
            #expect(result.albums == albums.filter(query.matches), "\(text)")
            #expect(result.tracks == albums.flatMap(\.tracks).filter(query.matches), "\(text)")
        }
    }
}
