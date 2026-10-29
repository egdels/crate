// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Where each audiobook was left: a position per file, for the tracks
 * [Skip] treats as spoken word or long. Playing such a file again starts
 * there; playing its album starts at the file heard last. A file that
 * plays to its end is done and starts from the top next time. Kept apart
 * from the index like the favorites, in `bookmarks.json` (the same layout
 * as the Mac's); songs never get one. Immutable, like [History].
 */
data class Bookmarks(val entries: Map<String, Mark> = emptyMap()) {
    /** [position] seconds into the file; [saved] seconds since 1970, so
     *  the album resumes at its newest mark. */
    @Serializable
    data class Mark(val position: Double, val saved: Double)

    val count: Int get() = entries.size

    /** Remembers the position for a spoken or long track; any other
     *  track is left out. Back at the very start, the mark goes: a file
     *  just begun (or begun again) is no place to come back to, and the
     *  last file of a finished book, back at 0, must not become the
     *  album's newest mark. */
    fun set(track: Track, position: Double, nowMillis: Long = System.currentTimeMillis()): Bookmarks {
        if (!Skip.isOffered(track)) return this
        if (position < MINIMUM_POSITION) return finish(track)
        var next = entries + (track.id to Mark(position, nowMillis / 1000.0))
        if (next.size > LIMIT) {
            val oldest = next.entries.sortedBy { it.value.saved }.take(next.size - LIMIT).map { it.key }.toSet()
            next = next - oldest
        }
        return Bookmarks(next)
    }

    /** The track was heard to its end. */
    fun finish(track: Track): Bookmarks = if (track.id in entries) Bookmarks(entries - track.id) else this

    /** Where to start the track: its mark, unless that is at the very
     *  start or so close to the end that it was as good as finished. */
    fun position(track: Track): Double? {
        if (!Skip.isOffered(track)) return null
        val mark = entries[track.id] ?: return null
        if (mark.position < MINIMUM_POSITION) return null
        if (track.duration > 0 && mark.position > track.duration - END_MARGIN) return null
        return mark.position
    }

    /** The index of the track heard last among the given ones (an
     *  album), null when none has a mark. */
    fun resumeIndex(tracks: List<Track>): Int? {
        var best: Int? = null
        var bestSaved = 0.0
        for ((index, track) in tracks.withIndex()) {
            val mark = entries[track.id] ?: continue
            if (!Skip.isOffered(track)) continue
            if (best == null || mark.saved > bestSaved) {
                best = index
                bestSaved = mark.saved
            }
        }
        val index = best ?: return null
        // Stopped in the last seconds of a file: on with the next one.
        return if (position(tracks[index]) == null && index + 1 < tracks.size) index + 1 else index
    }

    fun removeAllUnder(folderPath: String): Bookmarks = Bookmarks(entries.filterKeys { !Paths.isUnder(it, folderPath) })

    companion object {
        /** The oldest marks go beyond this many. */
        const val LIMIT = 1000
        /** Closer to the start than this is no place worth coming back to. */
        const val MINIMUM_POSITION = 5.0
        /** Closer to the end than this, the file counts as heard. */
        const val END_MARGIN = 10.0
    }
}

interface BookmarksStore {
    /** null when nothing has been saved yet. */
    fun load(): Bookmarks?
    fun save(bookmarks: Bookmarks)
}

/** `bookmarks.json` next to the library's index. */
class JsonBookmarksStore(val file: File) : BookmarksStore {
    @Serializable
    private data class FileContents(val version: Int = CURRENT_VERSION, val tracks: Map<String, Bookmarks.Mark> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    override fun load(): Bookmarks? {
        if (!file.exists()) return null
        return Bookmarks(json.decodeFromString(FileContents.serializer(), file.readText()).tracks)
    }

    override fun save(bookmarks: Bookmarks) {
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(json.encodeToString(FileContents.serializer(), FileContents(tracks = bookmarks.entries.toSortedMap())))
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) throw java.io.IOException("could not replace ${file.path}")
        }
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}
