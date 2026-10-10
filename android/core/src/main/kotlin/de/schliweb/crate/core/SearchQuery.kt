// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

/**
 * What the search field means: every whitespace-separated word has to
 * occur somewhere in a track's title, artist, album artist or album,
 * ignoring case and diacritics ("senorita" finds "Señorita"). An album
 * matches when its title or artist does, or when any of its tracks does,
 * so searching for a song title still shows the album it is on.
 */
class SearchQuery(text: String) {
    val words: List<String> = text.split(WHITESPACE).filter { it.isNotEmpty() }.map { it.foldedForSearch() }

    val isEmpty: Boolean get() = words.isEmpty()

    fun matches(track: Track): Boolean {
        if (isEmpty) return true
        val fields = listOfNotNull(track.title, track.artist, track.albumArtist, track.album).map { it.foldedForSearch() }
        return words.all { word -> fields.any { it.contains(word) } }
    }

    fun matches(album: Album): Boolean {
        if (isEmpty) return true
        val fields = listOf(album.title, album.artist).map { it.foldedForSearch() }
        if (words.all { word -> fields.any { it.contains(word) } }) return true
        return album.tracks.any(::matches)
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
