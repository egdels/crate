// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The tracks the user marked with a heart, as a set of track ids (document
 * paths). Kept apart from the library on purpose: a [Track] is rebuilt
 * whenever its file changes, and the library's index is megabytes that
 * should not be rewritten for one tap on a heart. Ids of files that are
 * gone stay in the set; they do no harm and the heart is still there when
 * the files come back. Only removing a folder from the library drops the
 * ids under it.
 *
 * Immutable: every change returns a new value, which is what Compose
 * state wants.
 */
data class Favorites(val ids: Set<String> = emptySet()) {
    val isEmpty: Boolean get() = ids.isEmpty()
    val size: Int get() = ids.size

    operator fun contains(track: Track): Boolean = track.id in ids

    /** Whether every one of the tracks is a favorite; false for none. */
    fun containsAll(tracks: List<Track>): Boolean = tracks.isNotEmpty() && tracks.all { it.id in ids }

    fun toggle(track: Track): Favorites =
        Favorites(if (track.id in ids) ids - track.id else ids + track.id)

    fun set(on: Boolean, tracks: List<Track>): Favorites {
        val changed = tracks.map { it.id }
        return Favorites(if (on) ids + changed else ids - changed.toSet())
    }

    /** The favorites among the given tracks, in the given order. */
    fun filter(tracks: List<Track>): List<Track> = tracks.filter { it.id in ids }

    /** Forgets every id under the folder, for when the folder leaves the
     *  library. */
    fun removeAllUnder(folderPath: String): Favorites =
        Favorites(ids.filterTo(HashSet()) { !Paths.isUnder(it, folderPath) })
}

interface FavoritesStore {
    /** null when nothing has been saved yet. */
    fun load(): Favorites?
    fun save(favorites: Favorites)
}

/** `favorites.json` next to the library's index: a version and the ids,
 *  sorted so that the file is the same for the same set. Written to a
 *  sibling first and renamed into place, like the index. */
class JsonFavoritesStore(val file: File) : FavoritesStore {
    @Serializable
    private data class FileContents(val version: Int = CURRENT_VERSION, val ids: List<String> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    override fun load(): Favorites? {
        if (!file.exists()) return null
        return Favorites(json.decodeFromString(FileContents.serializer(), file.readText()).ids.toHashSet())
    }

    override fun save(favorites: Favorites) {
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(json.encodeToString(FileContents.serializer(), FileContents(ids = favorites.ids.sorted())))
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) throw java.io.IOException("could not replace ${file.path}")
        }
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}
