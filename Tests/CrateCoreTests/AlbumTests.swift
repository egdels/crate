// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

import Foundation
import Testing
@testable import CrateCore

@Suite struct AlbumTests {
    @Test func groupsByAlbumArtistAndTitleCaseInsensitively() {
        let tracks = [
            makeTrack("x2", artist: "Guest", album: "Blue", albumArtist: "Band", track: 2),
            makeTrack("x1", artist: "band", album: "blue", track: 1),
            makeTrack("y1", artist: "Other", album: "Red", track: 1, year: 1999),
        ]
        let albums = Album.group(tracks)
        #expect(albums.map(\.title) == ["Blue", "Red"])
        #expect(albums[0].artist == "Band")
        #expect(albums[0].tracks.map(\.title) == ["x1", "x2"])
        #expect(albums[1].year == 1999)
    }

    @Test func sortsByDiscThenTrackThenTitle() {
        let tracks = [
            makeTrack("c", album: "A", track: 1, disc: 2),
            makeTrack("b", album: "A", track: 2, disc: 1),
            makeTrack("a", album: "A", track: 1, disc: 1),
            makeTrack("zz", album: "A"),
            makeTrack("aa", album: "A"),
        ]
        let album = Album.group(tracks)[0]
        #expect(album.tracks.map(\.title) == ["a", "b", "aa", "zz", "c"])
    }

    @Test func untaggedTracksAreNamedAfterTheirFolderAndItsParent() {
        let track = makeTrack("loose", folder: "/music/Harbour Sessions/Vol 1")
        let albums = Album.group([track], roots: [URL(fileURLWithPath: "/music")])
        #expect(albums.count == 1)
        #expect(albums[0].title == "Vol 1")
        #expect(albums[0].artist == "Harbour Sessions")

        // Directly under the watched folder there is no artist to take.
        let flat = Album.group([makeTrack("loose", folder: "/music/dune")], roots: [URL(fileURLWithPath: "/music")])
        #expect(flat[0].artist == Album.unknownArtist)
        #expect(Album.group([makeTrack("loose", folder: "/music")], roots: [URL(fileURLWithPath: "/music")])[0].artist == Album.unknownArtist)
    }

    @Test func oneMistaggedTrackDoesNotMakeACompilation() {
        var tracks = (1...6).map { makeTrack(String($0), artist: "Moss Quartet", album: "Landfall", track: $0, folder: "/m/a") }
        tracks[5].artist = "Landfall;"
        let albums = Album.group(tracks)
        #expect(albums.count == 1)
        #expect(albums[0].artist == "Moss Quartet")
    }

    @Test func siblingFoldersWithTheSameAlbumAreItsDiscs() {
        let tracks = [
            makeTrack("1", artist: "VA", album: "Soul", track: 1, folder: "/m/soul_1-2/soul_1"),
            makeTrack("2", artist: "VA", album: "Soul", track: 2, folder: "/m/soul_1-2/soul_1"),
            makeTrack("3", artist: "VA", album: "Soul", track: 1, folder: "/m/soul_1-2/soul_2"),
            // Same title by someone else next door is not a disc of it.
            makeTrack("4", artist: "Other", album: "Soul", track: 1, folder: "/m/soul_1-2/other"),
        ]
        let albums = Album.group(tracks)
        #expect(albums.count == 2)
        let merged = albums.first { $0.artist == "VA" }!
        #expect(merged.tracks.count == 3)
        #expect(merged.folder.path == "/m/soul_1-2")
    }

    @Test func compilationWithoutAlbumArtistIsOneAlbumByVariousArtists() {
        let tracks = [
            makeTrack("1", artist: "Arbor", album: "Sampler 100", track: 1, folder: "/music/sampler"),
            makeTrack("2", artist: "Cinder", album: "Sampler 100", track: 2, folder: "/music/sampler"),
            makeTrack("3", artist: "Delta", album: "Sampler 100", track: 3, folder: "/music/sampler"),
        ]
        let albums = Album.group(tracks)
        #expect(albums.count == 1)
        #expect(albums[0].artist == Album.variousArtists)
        #expect(albums[0].tracks.count == 3)

        // A per-track album artist tag that names each track's artist is
        // the same situation, not three albums.
        let perTrack = tracks.map { track in
            var t = track; t.albumArtist = track.artist; return t
        }
        #expect(Album.group(perTrack).map(\.artist) == [Album.variousArtists])
    }

    @Test func spellingNoiseInTitleOrArtistDoesNotSplitAFolder() {
        let tracks = [
            makeTrack("1", artist: "Moss Quartet", album: "Winter-Works", track: 1, folder: "/m/a"),
            makeTrack("2", artist: "Moss Quartet", album: "Winter-Works", track: 2, folder: "/m/a"),
            makeTrack("3", artist: "Moss Quartet;", album: "Winterworks", track: 3, folder: "/m/a"),
        ]
        let albums = Album.group(tracks)
        #expect(albums.count == 1)
        #expect(albums[0].title == "Winter-Works", "the majority spelling")
        #expect(albums[0].artist == "Moss Quartet")
    }

    @Test func untaggedTracksJoinTheFolderOnlyTaggedAlbum() {
        let tracks = [
            makeTrack("1", artist: "Dune", album: "First Light", track: 1, folder: "/m/dune"),
            makeTrack("2", folder: "/m/dune"),
            makeTrack("3", folder: "/m/dune"),
        ]
        let albums = Album.group(tracks)
        #expect(albums.count == 1)
        #expect(albums[0].title == "First Light")
        #expect(albums[0].tracks.count == 3)

        // With two tagged albums in the folder there is no telling; the
        // untagged ones stay on their own.
        let ambiguous = tracks + [makeTrack("4", artist: "Dune", album: "Second Wind", folder: "/m/dune")]
        #expect(Album.group(ambiguous).count == 3)
    }

    @Test func discSubfoldersBelongToTheParentAlbum() {
        let tracks = [
            makeTrack("1", artist: "A", album: "Live", track: 1, disc: 1, folder: "/m/live/CD1"),
            makeTrack("2", artist: "A", album: "Live", track: 1, disc: 2, folder: "/m/live/Disc 2"),
        ]
        let albums = Album.group(tracks)
        #expect(albums.count == 1)
        #expect(albums[0].folder.path == "/m/live")
        #expect(albums[0].tracks.map(\.title) == ["1", "2"])
        #expect(Album.groupingFolder(of: URL(fileURLWithPath: "/m/cdrom/x.mp3")).path == "/m/cdrom")
    }

    @Test func sameTagsInDifferentFoldersAreDifferentAlbums() {
        let tracks = [
            makeTrack("1", artist: "A", album: "Greatest Hits", folder: "/m/one"),
            makeTrack("2", artist: "A", album: "Greatest Hits", folder: "/m/two"),
        ]
        let albums = Album.group(tracks)
        #expect(albums.count == 2)
        #expect(Set(albums.map(\.id)).count == 2)
    }

    @Test func albumsSortByArtistYearTitle() {
        let tracks = [
            makeTrack("1", artist: "B", album: "Late", year: 2001),
            makeTrack("2", artist: "B", album: "Early", year: 1990),
            makeTrack("3", artist: "A", album: "Z"),
        ]
        #expect(Album.group(tracks).map(\.title) == ["Z", "Early", "Late"])
    }

    @Test func aNumberedSeriesSortsByItsNumbersNotTheEditionsYear() {
        let tracks = [
            makeTrack("a", artist: "Die drei ???", album: "Die drei ??? Volume 98: Tal", year: 2001, folder: "/m/98"),
            makeTrack("b", artist: "Die drei ???", album: "Die drei ??? Volume 36: Wal", year: 2010, folder: "/m/36"),
            makeTrack("c", artist: "Die drei ???", album: "Die drei ??? Volume 3: Hund", year: 1979, folder: "/m/3"),
            makeTrack("d", artist: "Die drei ???", album: "Folge 194: Zeitreisende", folder: "/m/194"),
            makeTrack("e", artist: "Die drei ???", album: "Die drei ??? und der 5. Advent", year: 2012, folder: "/m/advent"),
            makeTrack("f", artist: "Die drei ???", album: "Folge 120: Skorpion", year: 2005, folder: "/m/120"),
        ]
        #expect(Album.group(tracks).map(\.title) == [
            "Die drei ??? Volume 3: Hund", "Die drei ??? Volume 36: Wal", "Die drei ??? Volume 98: Tal",
            "Folge 120: Skorpion", "Folge 194: Zeitreisende", "Die drei ??? und der 5. Advent",
        ])
    }

    @Test func aNumberAtTheEndAndAnAbbreviationCountToo() {
        let titles = ["Der böse Ort: Peter Grant 4", "Schwarzer Mond: Peter Grant 2", "Ein Wispern: Peter Grant 3"]
        #expect(Album.seriesNumbers(titles) == [4, 2, 3])
        let beatles = ["Deluxe Vol. 1 (Disc 2)", "Deluxe Vol. 1 (Disc 1)", "Deluxe, Volume 2", "Deluxe Vol. 3", "White Album"]
        #expect(Album.seriesNumbers(beatles) == [1, 1, 2, 3, nil])
    }

    @Test func numbersNowAndThenAreNoSeries() {
        #expect(Album.seriesNumbers(["Calling Card", "Cologne 1990", "Japan Tour 74", "Jinx", "Blues Box CD 2"]) == nil)
        // Two numbered titles are too few, and one number repeated is a copy.
        #expect(Album.seriesNumbers(["Vol. 1", "Vol. 2", "Blue"]) == nil)
        #expect(Album.seriesNumbers(["Band 1-4", "Band 1-4", "Band 1-4"]) == nil)
        let tracks = [
            makeTrack("1", artist: "B", album: "Live 1991", year: 1991, folder: "/m/1"),
            makeTrack("2", artist: "B", album: "Hits Vol. 2", year: 1980, folder: "/m/2"),
            makeTrack("3", artist: "B", album: "Blue", year: 1975, folder: "/m/3"),
        ]
        #expect(Album.group(tracks).map(\.title) == ["Blue", "Hits Vol. 2", "Live 1991"])
    }

    @Test func onlyFreeStandingNumbersCount() {
        let found = Album.numberedWords("Die Dr3i 5: Das Haus der 1.000 Rätsel, CD 2, Teil 12345")
        #expect(found.map(\.word) == ["dr3i"])
        #expect(found.map(\.number) == [5])
        #expect(Album.numberedWords("001 - Anfang").map(\.word) == [""])
    }

    @Test func aLeadingTheDoesNotDecideTheOrder() {
        let tracks = [
            makeTrack("1", artist: "The Beatles", album: "Help!", folder: "/m/beatles"),
            makeTrack("2", artist: "Arcade Fire", album: "Funeral", folder: "/m/arcade"),
            makeTrack("3", artist: "Can", album: "Ege Bamyasi", folder: "/m/can"),
            makeTrack("4", artist: "Theo Parrish", album: "Parallel", folder: "/m/theo"),
        ]
        #expect(Album.group(tracks).map(\.artist) == ["Arcade Fire", "The Beatles", "Can", "Theo Parrish"])
        #expect(Album.sortName("The Beatles") == "Beatles, The")
        #expect(Album.sortName("Theo Parrish") == "Theo Parrish")
        #expect(Album.sortName("The ") == "The ".trimmed)
    }

    @Test func sortTagsWinOverTheName() {
        var beatles = makeTrack("1", artist: "The Beatles", album: "Help!", folder: "/m/beatles")
        beatles.artistSort = "Beatles, The"
        var zappa = makeTrack("2", artist: "Frank Zappa", album: "Apostrophe", folder: "/m/zappa")
        zappa.artistSort = "Zappa, Frank"
        var dylan = makeTrack("3", artist: "Bob Dylan", album: "Desire", albumArtist: "Bob Dylan", folder: "/m/dylan")
        dylan.artistSort = "Dylan, Bob"
        dylan.albumArtistSort = "Aaa"  // the album artist's sort tag is the one that counts
        var titled = makeTrack("4", artist: "Can", album: "The Lost Tapes", folder: "/m/can")
        titled.albumSort = "Lost Tapes, The"
        let albums = Album.group([beatles, zappa, dylan, titled])
        #expect(albums.map(\.artist) == ["Bob Dylan", "The Beatles", "Can", "Frank Zappa"])
        #expect(albums.map(\.sortArtist) == ["Aaa", "Beatles, The", "Can", "Zappa, Frank"])
        #expect(albums[2].sortTitle == "Lost Tapes, The")
    }

    @Test func compilationFlagMakesVariousArtistsWithoutAlbumArtist() {
        // A tribute album: one artist on two thirds of the tracks would
        // pass as the album's artist, but the tagger said compilation.
        var tracks = [
            makeTrack("1", artist: "Moss Quartet", album: "Tribute", track: 1, folder: "/m/t"),
            makeTrack("2", artist: "Moss Quartet", album: "Tribute", track: 2, folder: "/m/t"),
            makeTrack("3", artist: "Guest", album: "Tribute", track: 3, folder: "/m/t"),
        ]
        #expect(Album.group(tracks)[0].artist == "Moss Quartet")
        for i in tracks.indices { tracks[i].compilation = true }
        #expect(Album.group(tracks)[0].artist == Album.variousArtists)

        // The flag on a minority of tracks changes nothing, and an album
        // artist tag beats the flag: iTunes sets it on single-artist
        // albums filed under a name too.
        tracks[2].compilation = false
        tracks[1].compilation = false
        #expect(Album.group(tracks)[0].artist == "Moss Quartet")
        for i in tracks.indices { tracks[i].compilation = true; tracks[i].albumArtist = "Moss Quartet" }
        #expect(Album.group(tracks)[0].artist == "Moss Quartet")
    }

    @Test func genreIsTheMajorityOfTheTracks() {
        var tracks = (1...3).map { makeTrack(String($0), artist: "A", album: "B", track: $0) }
        #expect(Album.group(tracks)[0].genre == nil)
        tracks[0].genre = "Jazz"
        tracks[1].genre = "Jazz"
        tracks[2].genre = "Rock"
        #expect(Album.group(tracks)[0].genre == "Jazz")
    }

    @Test func discsGroupTheTracksByDiscNumber() {
        let tracks = [
            makeTrack("b", album: "Box", track: 1, disc: 2), makeTrack("a2", album: "Box", track: 2, disc: 1),
            makeTrack("a1", album: "Box", track: 1, disc: 1),
        ]
        let album = Album.group(tracks, roots: [URL(fileURLWithPath: "/music")])[0]
        let discs = album.discs
        #expect(discs.map(\.number) == [1, 2])
        #expect(discs[0].tracks.map(\.title) == ["a1", "a2"])
        #expect(discs[1].tracks.map(\.title) == ["b"])
    }

    @Test func untaggedDiscsAreOneDisc() {
        let tracks = [makeTrack("a", album: "One", track: 1), makeTrack("b", album: "One", track: 2)]
        let album = Album.group(tracks, roots: [URL(fileURLWithPath: "/music")])[0]
        #expect(album.discs.count == 1)
        #expect(album.discs[0].number == 1)
        #expect(album.discs[0].tracks.count == 2)
    }

    @Test func untaggedDiscFoldersPlayOneDiscAfterTheOther() {
        // A rip without disc tags: CD1 and CD2 are one album, and their
        // tracks must not interleave (CD1-01, CD2-01, CD1-02, ...).
        let tracks = [
            makeTrack("a1", artist: "A", album: "Live", track: 1, folder: "/m/live/CD1"),
            makeTrack("b1", artist: "A", album: "Live", track: 1, folder: "/m/live/CD2"),
            makeTrack("a2", artist: "A", album: "Live", track: 2, folder: "/m/live/CD1"),
            makeTrack("b2", artist: "A", album: "Live", track: 2, folder: "/m/live/CD2"),
        ]
        let album = Album.group(tracks)[0]
        #expect(album.tracks.map(\.title) == ["a1", "a2", "b1", "b2"])
        #expect(album.discs.map(\.number) == [1, 2])

        // The sibling merge ("soul_1", "soul_2") orders the same way.
        let soul = [
            makeTrack("1", artist: "VA", album: "Soul", track: 1, folder: "/m/soul/soul_1"),
            makeTrack("3", artist: "VA", album: "Soul", track: 1, folder: "/m/soul/soul_2"),
            makeTrack("2", artist: "VA", album: "Soul", track: 2, folder: "/m/soul/soul_1"),
        ]
        let merged = Album.group(soul)[0]
        #expect(merged.tracks.map(\.title) == ["1", "2", "3"])
        #expect(merged.discs.map(\.number) == [1, 2])

        // A disc tag still wins over the folder.
        var tagged = tracks
        for i in tagged.indices { tagged[i].discNumber = tagged[i].title.hasPrefix("a") ? 2 : 1 }
        #expect(Album.group(tagged)[0].tracks.map(\.title) == ["b1", "b2", "a1", "a2"])
    }

    @Test func siblingMergeKeepsTheParentsOwnTracks() {
        let tracks = [
            makeTrack("main", artist: "A", album: "Album", track: 1, folder: "/m/Album"),
            makeTrack("x1", artist: "A", album: "Album", track: 1, folder: "/m/Album/Bonus 1"),
            makeTrack("x2", artist: "A", album: "Album", track: 1, folder: "/m/Album/Bonus 2"),
        ]
        let albums = Album.group(tracks)
        #expect(albums.count == 1)
        #expect(albums[0].tracks.map(\.title) == ["main", "x1", "x2"])

        // Two sibling groups with one title under one parent: neither
        // loses its tracks.
        let two = [
            makeTrack("p1", artist: "P", album: "Soul", folder: "/m/s/soul_1"),
            makeTrack("p2", artist: "P", album: "Soul", folder: "/m/s/soul_2"),
            makeTrack("q1", artist: "Q", album: "Soul", folder: "/m/s/funk_1"),
            makeTrack("q2", artist: "Q", album: "Soul", folder: "/m/s/funk_2"),
        ]
        #expect(Album.group(two).flatMap(\.tracks).count == 4)
    }

    @Test func compilationsSortAsVariousArtists() {
        var tracks = [
            makeTrack("1", artist: "The Beatles", album: "Hits", track: 1, folder: "/m/hits"),
            makeTrack("2", artist: "Cream", album: "Hits", track: 2, folder: "/m/hits"),
            makeTrack("3", artist: "The Doors", album: "Hits", track: 3, folder: "/m/hits"),
        ]
        tracks[0].artistSort = "Beatles, The"
        tracks[1].artistSort = "Cream"
        tracks[2].artistSort = "Doors, The"
        let album = Album.group(tracks)[0]
        #expect(album.artist == Album.variousArtists)
        #expect(album.sortArtist == Album.variousArtists)

        // Album artist sort tags that agree still count.
        for i in tracks.indices { tracks[i].albumArtistSort = "Hits Collection" }
        tracks[0].compilation = true; tracks[1].compilation = true
        #expect(Album.group(tracks)[0].sortArtist == "Hits Collection")
    }

    /// One folder tagged by two tools, one writing decomposed (NFD)
    /// umlauts: one album and one artist, as on Android.
    @Test func composedAndDecomposedSpellingsAreOneAlbum() {
        let nfc = "Bj\u{00F6}rk \u{00D6}dland", nfd = "Bjo\u{0308}rk O\u{0308}dland"
        #expect(Array(nfc.unicodeScalars) != Array(nfd.unicodeScalars))
        let tracks = [
            makeTrack("1", artist: nfc, album: "\u{00DC}ber " + nfc, track: 1, folder: "/m/a"),
            makeTrack("2", artist: nfd, album: "U\u{0308}ber " + nfd, track: 2, folder: "/m/a"),
        ]
        let albums = Album.group(tracks)
        #expect(albums.count == 1)
        #expect(albums[0].tracks.count == 2)
        #expect(albums[0].artist != Album.variousArtists)
    }
}
