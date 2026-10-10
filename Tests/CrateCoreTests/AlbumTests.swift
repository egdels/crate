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
        var tracks = (1...6).map { makeTrack("\($0)", artist: "Moss Quartet", album: "Landfall", track: $0, folder: "/m/a") }
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
}
