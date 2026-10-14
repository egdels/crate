// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

/**
 * [SearchQuery] over a whole library, with every field folded once when
 * the index is built instead of once per keystroke: folding (NFD plus a
 * regex) is what made typing lag with 13,000 tracks. Same results as
 * filtering with [SearchQuery.matches].
 */
class SearchIndex(val albums: List<Album>) {
    /** Every track in album order. */
    val tracks: List<Track> = albums.flatMap { it.tracks }

    // The folded fields of one item joined by newlines: a word never
    // contains whitespace, so it cannot match across two fields.
    private val albumTexts: List<String> = albums.map { album ->
        SearchQuery.fields(album).joinToString("\n") { it.foldedForSearch() }
    }
    private val trackTexts: List<String> = tracks.map { track ->
        SearchQuery.fields(track).joinToString("\n") { it.foldedForSearch() }
    }
    /** The folded artist fields alone, for the artist view. */
    private val albumArtists: List<String> = albums.map { it.artist.foldedForSearch() }
    private val trackArtists: List<String> = tracks.map { track ->
        SearchQuery.artistFields(track).joinToString("\n") { it.foldedForSearch() }
    }

    data class Result(val albums: List<Album>, val tracks: List<Track>)

    fun search(query: SearchQuery): Result {
        if (query.isEmpty) return Result(albums, tracks)
        val artist = query.artist
        fun matches(text: String) = query.words.all { text.contains(it) }
        val trackMatches = BooleanArray(tracks.size) { if (artist != null) trackArtists[it].contains(artist) else matches(trackTexts[it]) }
        val albumMatches = BooleanArray(albums.size) { if (artist != null) albumArtists[it].contains(artist) else matches(albumTexts[it]) }
        val foundAlbums = mutableListOf<Album>()
        var start = 0
        albums.forEachIndexed { i, album ->
            val end = start + album.tracks.size
            if (albumMatches[i] || (start until end).any { trackMatches[it] }) foundAlbums.add(album)
            start = end
        }
        return Result(foundAlbums, tracks.filterIndexed { i, _ -> trackMatches[i] })
    }
}
