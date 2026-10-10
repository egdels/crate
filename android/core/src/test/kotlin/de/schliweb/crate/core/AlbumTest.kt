// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlbumTest {
    @Test
    fun groupsByAlbumArtistAndTitleCaseInsensitively() {
        val tracks = listOf(
            makeTrack("x2", artist = "Guest", album = "Blue", albumArtist = "Band", track = 2),
            makeTrack("x1", artist = "band", album = "blue", track = 1),
            makeTrack("y1", artist = "Other", album = "Red", track = 1, year = 1999),
        )
        val albums = Album.group(tracks)
        assertEquals(listOf("Blue", "Red"), albums.map { it.title })
        assertEquals("Band", albums[0].artist)
        assertEquals(listOf("x1", "x2"), albums[0].tracks.map { it.title })
        assertEquals(1999, albums[1].year)
    }

    @Test
    fun sortsByDiscThenTrackThenTitle() {
        val tracks = listOf(
            makeTrack("c", album = "A", track = 1, disc = 2),
            makeTrack("b", album = "A", track = 2, disc = 1),
            makeTrack("a", album = "A", track = 1, disc = 1),
            makeTrack("zz", album = "A"),
            makeTrack("aa", album = "A"),
        )
        assertEquals(listOf("a", "b", "aa", "zz", "c"), Album.group(tracks)[0].tracks.map { it.title })
    }

    @Test
    fun untaggedTracksAreNamedAfterTheirFolderAndItsParent() {
        val albums = Album.group(listOf(makeTrack("loose", folder = "/music/Harbour Sessions/Vol 1")), roots = listOf("/music"))
        assertEquals(1, albums.size)
        assertEquals("Vol 1", albums[0].title)
        assertEquals("Harbour Sessions", albums[0].artist)

        // Directly under the watched folder there is no artist to take.
        assertEquals(Album.UNKNOWN_ARTIST, Album.group(listOf(makeTrack("loose", folder = "/music/dune")), listOf("/music"))[0].artist)
        assertEquals(Album.UNKNOWN_ARTIST, Album.group(listOf(makeTrack("loose", folder = "/music")), listOf("/music"))[0].artist)
    }

    @Test
    fun documentPathsWithoutLeadingSlashWorkTheSame() {
        // Android paths start with the tree's document id, not "/".
        val root = "primary:Music"
        val albums = Album.group(listOf(makeTrack("loose", folder = "$root/Harbour Sessions/Vol 1")), roots = listOf(root))
        assertEquals("Harbour Sessions", albums[0].artist)
        assertEquals(Album.UNKNOWN_ARTIST, Album.group(listOf(makeTrack("loose", folder = "$root/dune")), listOf(root))[0].artist)
        assertEquals(Album.UNKNOWN_ARTIST, Album.group(listOf(makeTrack("loose", folder = root)), listOf(root))[0].artist)
    }

    @Test
    fun oneMistaggedTrackDoesNotMakeACompilation() {
        val tracks = (1..6).map { makeTrack("$it", artist = "Moss Quartet", album = "Landfall", track = it, folder = "/m/a") }
            .toMutableList()
        tracks[5] = tracks[5].copy(artist = "Landfall;")
        val albums = Album.group(tracks)
        assertEquals(1, albums.size)
        assertEquals("Moss Quartet", albums[0].artist)
    }

    @Test
    fun siblingFoldersWithTheSameAlbumAreItsDiscs() {
        val tracks = listOf(
            makeTrack("1", artist = "VA", album = "Soul", track = 1, folder = "/m/soul_1-2/soul_1"),
            makeTrack("2", artist = "VA", album = "Soul", track = 2, folder = "/m/soul_1-2/soul_1"),
            makeTrack("3", artist = "VA", album = "Soul", track = 1, folder = "/m/soul_1-2/soul_2"),
            // Same title by someone else next door is not a disc of it.
            makeTrack("4", artist = "Other", album = "Soul", track = 1, folder = "/m/soul_1-2/other"),
        )
        val albums = Album.group(tracks)
        assertEquals(2, albums.size)
        val merged = albums.first { it.artist == "VA" }
        assertEquals(3, merged.tracks.size)
        assertEquals("/m/soul_1-2", merged.folder)
    }

    @Test
    fun compilationWithoutAlbumArtistIsOneAlbumByVariousArtists() {
        val tracks = listOf(
            makeTrack("1", artist = "Arbor", album = "Sampler 100", track = 1, folder = "/music/sampler"),
            makeTrack("2", artist = "Cinder", album = "Sampler 100", track = 2, folder = "/music/sampler"),
            makeTrack("3", artist = "Delta", album = "Sampler 100", track = 3, folder = "/music/sampler"),
        )
        val albums = Album.group(tracks)
        assertEquals(1, albums.size)
        assertEquals(Album.VARIOUS_ARTISTS, albums[0].artist)
        assertEquals(3, albums[0].tracks.size)

        // A per-track album artist tag that names each track's artist is
        // the same situation, not three albums.
        val perTrack = tracks.map { it.copy(albumArtist = it.artist) }
        assertEquals(listOf(Album.VARIOUS_ARTISTS), Album.group(perTrack).map { it.artist })
    }

    @Test
    fun spellingNoiseInTitleOrArtistDoesNotSplitAFolder() {
        val tracks = listOf(
            makeTrack("1", artist = "Moss Quartet", album = "Winter-Works", track = 1, folder = "/m/a"),
            makeTrack("2", artist = "Moss Quartet", album = "Winter-Works", track = 2, folder = "/m/a"),
            makeTrack("3", artist = "Moss Quartet;", album = "Winterworks", track = 3, folder = "/m/a"),
        )
        val albums = Album.group(tracks)
        assertEquals(1, albums.size)
        assertEquals("Winter-Works", albums[0].title, "the majority spelling")
        assertEquals("Moss Quartet", albums[0].artist)
    }

    @Test
    fun untaggedTracksJoinTheFolderOnlyTaggedAlbum() {
        val tracks = listOf(
            makeTrack("1", artist = "Dune", album = "First Light", track = 1, folder = "/m/dune"),
            makeTrack("2", folder = "/m/dune"),
            makeTrack("3", folder = "/m/dune"),
        )
        val albums = Album.group(tracks)
        assertEquals(1, albums.size)
        assertEquals("First Light", albums[0].title)
        assertEquals(3, albums[0].tracks.size)

        // With two tagged albums in the folder there is no telling; the
        // untagged ones stay on their own.
        val ambiguous = tracks + makeTrack("4", artist = "Dune", album = "Second Wind", folder = "/m/dune")
        assertEquals(3, Album.group(ambiguous).size)
    }

    @Test
    fun discSubfoldersBelongToTheParentAlbum() {
        val tracks = listOf(
            makeTrack("1", artist = "A", album = "Live", track = 1, disc = 1, folder = "/m/live/CD1"),
            makeTrack("2", artist = "A", album = "Live", track = 1, disc = 2, folder = "/m/live/Disc 2"),
        )
        val albums = Album.group(tracks)
        assertEquals(1, albums.size)
        assertEquals("/m/live", albums[0].folder)
        assertEquals(listOf("1", "2"), albums[0].tracks.map { it.title })
        assertEquals("/m/cdrom", Album.groupingFolder("/m/cdrom/x.mp3"))
    }

    @Test
    fun sameTagsInDifferentFoldersAreDifferentAlbums() {
        val tracks = listOf(
            makeTrack("1", artist = "A", album = "Greatest Hits", folder = "/m/one"),
            makeTrack("2", artist = "A", album = "Greatest Hits", folder = "/m/two"),
        )
        val albums = Album.group(tracks)
        assertEquals(2, albums.size)
        assertEquals(2, albums.map { it.id }.toSet().size)
    }

    @Test
    fun albumsSortByArtistYearTitle() {
        val tracks = listOf(
            makeTrack("1", artist = "B", album = "Late", year = 2001),
            makeTrack("2", artist = "B", album = "Early", year = 1990),
            makeTrack("3", artist = "A", album = "Z"),
        )
        assertEquals(listOf("Z", "Early", "Late"), Album.group(tracks).map { it.title })
    }

    @Test
    fun naturalOrderComparesNumbersByValue() {
        assertEquals(listOf("Track 2", "Track 10", "track 11"), listOf("track 11", "Track 10", "Track 2").sortedWith(::naturalCompare))
        assertTrue(naturalCompare("a", "B") < 0)
        assertTrue(naturalCompare("Éa", "eb") < 0)
    }
}
