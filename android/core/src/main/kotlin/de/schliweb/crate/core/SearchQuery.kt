// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

/**
 * What the search field means: every whitespace-separated word has to
 * occur somewhere in a track's title, artist, album artist, album,
 * composer or genre, ignoring case and diacritics ("senorita" finds
 * "Señorita"). An album matches when its title, artist or genre does,
 * or when any of its tracks does, so searching for a song title still
 * shows the album it is on.
 *
 * "artist:Name" is the artist view: the rest of the text is one phrase
 * that has to occur in the artist or album artist, nothing else is
 * searched. The album header's artist name fills the field that way.
 */
class SearchQuery(text: String) {
    /** The folded phrase after "artist:", null for an ordinary search. */
    val artist: String?
    val words: List<String>

    init {
        val trimmed = text.trim()
        if (trimmed.lowercase().startsWith(ARTIST_PREFIX)) {
            artist = trimmed.substring(ARTIST_PREFIX.length).trim().takeIf { it.isNotEmpty() }?.foldedForSearch()
            words = emptyList()
        } else {
            artist = null
            words = text.split(WHITESPACE).filter { it.isNotEmpty() }.map { it.foldedForSearch() }
        }
    }

    val isEmpty: Boolean get() = words.isEmpty() && artist == null

    fun matches(track: Track): Boolean {
        artist?.let { phrase -> return artistFields(track).any { it.foldedForSearch().contains(phrase) } }
        if (isEmpty) return true
        val fields = fields(track).map { it.foldedForSearch() }
        return words.all { word -> fields.any { it.contains(word) } }
    }

    fun matches(album: Album): Boolean {
        artist?.let { phrase -> return album.artist.foldedForSearch().contains(phrase) || album.tracks.any(::matches) }
        if (isEmpty) return true
        val fields = fields(album).map { it.foldedForSearch() }
        if (words.all { word -> fields.any { it.contains(word) } }) return true
        return album.tracks.any(::matches)
    }

    companion object {
        const val ARTIST_PREFIX = "artist:"
        private val WHITESPACE = Regex("\\s+")

        /** The text that shows the albums of one artist. */
        fun textForArtist(artist: String): String = ARTIST_PREFIX + artist

        fun artistFields(track: Track): List<String> = listOfNotNull(track.artist, track.albumArtist)

        /** The fields a search looks at; [SearchIndex] folds the same ones. */
        fun fields(track: Track): List<String> =
            listOfNotNull(track.title, track.artist, track.albumArtist, track.album, track.composer, track.genre)

        fun fields(album: Album): List<String> = listOfNotNull(album.title, album.artist, album.genre)
    }
}
