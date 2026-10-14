// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What was played, newest first: track ids, each once, capped. Kept
 * apart from the library like the favorites, in `history.json`, so a
 * track starting never rewrites the index. Ids of files that are gone
 * stay until a folder is removed. Immutable, like [Favorites].
 */
data class History(val ids: List<String> = emptyList()) {
    val isEmpty: Boolean get() = ids.isEmpty()

    /** Puts the track in front; a repeat moves it, so the list stays
     *  one entry per track. */
    fun record(id: String): History = History((listOf(id) + ids.filter { it != id }).take(LIMIT))

    /** The tracks in the history's order, skipping ids no longer in the
     *  given tracks. */
    fun tracks(from: List<Track>): List<Track> {
        val byId = from.associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    fun removeAllUnder(folderPath: String): History = History(ids.filter { !Paths.isUnder(it, folderPath) })

    companion object {
        const val LIMIT = 500
    }
}

interface HistoryStore {
    /** null when nothing has been saved yet. */
    fun load(): History?
    fun save(history: History)
}

/** `history.json` next to the library's index: a version and the ids. */
class JsonHistoryStore(val file: File) : HistoryStore {
    @Serializable
    private data class FileContents(val version: Int = CURRENT_VERSION, val ids: List<String> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    override fun load(): History? {
        if (!file.exists()) return null
        return History(json.decodeFromString(FileContents.serializer(), file.readText()).ids.distinct().take(History.LIMIT))
    }

    override fun save(history: History) {
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(json.encodeToString(FileContents.serializer(), FileContents(ids = history.ids)))
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) throw java.io.IOException("could not replace ${file.path}")
        }
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

/** The newest files first, for a Recently Added list: by the file's
 *  modification day (UTC), newest day first, and within one day in the
 *  given (album) order, so an album copied in one go stays together. */
fun recentlyAdded(tracks: List<Track>, limit: Int = 200): List<Track> {
    val day = 86_400_000L
    return tracks.withIndex()
        .sortedWith(compareByDescending<IndexedValue<Track>> { it.value.modified / day }.thenBy { it.index })
        .take(limit)
        .map { it.value }
}
