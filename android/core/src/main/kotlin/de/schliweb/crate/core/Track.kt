// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

import kotlinx.serialization.Serializable

/**
 * One audio file in the library, with the tags that matter for browsing
 * and playing it. Identified by its [path]: the library never holds the
 * same file twice.
 *
 * Two addresses, because Android separates them: [uri] is what opens the
 * file (a `content://` document URI from the Storage Access Framework, or
 * a `file://` URI), [path] is where it lies, as a "/"-separated string
 * built from the folder names on the way down from the watched folder.
 * Grouping into albums works on the path, which is stable and readable;
 * the URI is opaque and provider-specific.
 */
@Serializable
data class Track(
    val uri: String,
    val path: String,
    /** Size and modification time (epoch milliseconds) as seen at the
     *  last scan. A file whose size and time are unchanged is not re-read
     *  on the next rescan. */
    val fileSize: Long,
    val modified: Long,
    /** Never empty: falls back to the file name without extension when
     *  the file carries no title tag. */
    val title: String,
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val trackNumber: Int? = null,
    val trackTotal: Int? = null,
    val discNumber: Int? = null,
    val discTotal: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    /** Seconds. 0 when the file reports no duration. */
    val duration: Double = 0.0,
) {
    val id: String get() = path

    /** The artist an album is filed under: the album artist when tagged,
     *  otherwise the track artist. */
    val displayArtist: String? get() = albumArtist ?: artist
}

/**
 * What a [MetadataReader] extracts from one file. Everything is optional
 * because every field may be missing from the tags; [Library] turns this
 * into a [Track] by adding the file facts and the title fallback.
 */
data class TrackMetadata(
    val title: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val trackNumber: Int? = null,
    val trackTotal: Int? = null,
    val discNumber: Int? = null,
    val discTotal: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val duration: Double = 0.0,
)

/** Reads the tags and duration of one audio file. The library depends on
 *  this interface only, so tests run with a fake and the app with the
 *  Android implementation. */
interface MetadataReader {
    suspend fun read(file: ScannedFile): TrackMetadata
}
