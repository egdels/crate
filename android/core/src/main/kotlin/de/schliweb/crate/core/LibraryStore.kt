// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Everything the library persists between launches. The version goes
 *  up when a rescan has to re-read unchanged files, because the tags a
 *  [Track] holds grew: 2 added composer, compilation and the sort names,
 *  3 the loudness gains. */
@Serializable
data class LibrarySnapshot(
    /** Defaults to 1, not to the current version: the serializer leaves
     *  default values out of the file, so version 1 was written without
     *  the key, and a file without it has to read as version 1. Writers
     *  pass [CURRENT_VERSION] explicitly. */
    val version: Int = 1,
    val folders: List<Folder> = emptyList(),
    val tracks: List<Track> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 3
    }
}

interface LibraryStore {
    /** null when nothing has been saved yet. */
    fun load(): LibrarySnapshot?
    fun save(snapshot: LibrarySnapshot)
}

/** One JSON file, written to a sibling first and renamed into place, so
 *  a crash mid-write leaves the old index intact. */
class JsonLibraryStore(val file: File) : LibraryStore {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    override fun load(): LibrarySnapshot? {
        if (!file.exists()) return null
        return json.decodeFromString(LibrarySnapshot.serializer(), file.readText())
    }

    override fun save(snapshot: LibrarySnapshot) {
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(json.encodeToString(LibrarySnapshot.serializer(), snapshot))
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) throw java.io.IOException("could not replace ${file.path}")
        }
    }
}
