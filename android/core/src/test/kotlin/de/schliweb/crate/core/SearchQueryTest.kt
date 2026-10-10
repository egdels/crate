// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchQueryTest {
    private val tracks = listOf(
        makeTrack("Nobody Else But You", artist = "Ada Lark", album = "Evening Songs"),
        makeTrack("Morning Light", artist = "Ada Lark", album = "Night Letters"),
        makeTrack("Señorita", artist = "Río Trio", album = "Costa"),
    )

    @Test
    fun emptyQueryMatchesEverything() {
        val query = SearchQuery("   ")
        assertTrue(query.isEmpty)
        assertTrue(tracks.all(query::matches))
    }

    @Test
    fun everyWordMustMatchSomeField() {
        assertTrue(SearchQuery("ada nobody").matches(tracks[0]))
        assertFalse(SearchQuery("ada nobody").matches(tracks[1]))
        assertTrue(SearchQuery("night").matches(tracks[1]))
    }

    @Test
    fun ignoresCaseAndDiacritics() {
        assertTrue(SearchQuery("senorita").matches(tracks[2]))
        assertTrue(SearchQuery("RIO TRIO").matches(tracks[2]))
    }

    @Test
    fun albumMatchesThroughTitleArtistOrTrack() {
        val albums = Album.group(tracks)
        val spell = albums.first { it.title == "Night Letters" }
        val costa = albums.first { it.title == "Costa" }
        assertTrue(SearchQuery("morning").matches(spell))
        assertTrue(SearchQuery("lark night").matches(spell))
        assertFalse(SearchQuery("morning").matches(costa))
        assertTrue(SearchQuery("trio").matches(costa))
    }
}
