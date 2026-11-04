// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileNotFoundException

/** A folder the user added to the library: the URI that grants access
 *  (a document tree on Android), the path its tracks' paths start with,
 *  and a name to show. */
@Serializable
data class Folder(val uri: String, val path: String, val name: String)

/** A file the scanner found, with the two facts that decide whether it
 *  has to be re-read: size (-1 when the provider does not know it) and
 *  modification time. */
data class ScannedFile(val uri: String, val path: String, val fileSize: Long, val modified: Long)

/**
 * Walks a folder recursively and lists the audio files in it. Pure file
 * listing, no tag reading; that is [MetadataReader]'s job. On Android
 * the implementation queries the Storage Access Framework; on the JVM
 * [LocalFileSource] walks java.io.File, which is what the tests use.
 */
interface FileSource {
    /** Regular files under [folder] whose extension matches, hidden files
     *  skipped, sorted by path so results are stable. Throws when the
     *  folder cannot be listed. */
    suspend fun scan(folder: Folder): List<ScannedFile> = walk(folder).files

    /** [scan], plus the paths of the subfolders that could not be
     *  listed: known tracks under them are not gone. A rescan that took
     *  the silence for "deleted" pruned what a provider hiccup hid.
     *  Throws when [folder] itself cannot be listed. */
    suspend fun walk(folder: Folder): Walk
}

/** What a [FileSource.walk] found, and the folders it could not look into. */
data class Walk(val files: List<ScannedFile>, val unreadable: List<String> = emptyList())

/** What Android decodes without help. Unlike the Mac, Ogg Vorbis and
 *  Opus are included: the platform plays them. Audiobooks (m4b, an M4A
 *  by another name) are in too; whoever picks the folder wants to hear
 *  what is in it. Ringtones (m4r) are not. */
val DEFAULT_EXTENSIONS: Set<String> = setOf(
    "mp3", "m4a", "m4b", "aac", "flac", "wav", "ogg", "oga", "opus", "aiff", "aif", "mka",
)

/** Hidden names and the odd system file are skipped on every platform. */
fun isAudioFileName(name: String, extensions: Set<String> = DEFAULT_EXTENSIONS): Boolean =
    !name.startsWith(".") && Paths.extension(name) in extensions

/** java.io.File implementation: `Folder.uri` is a `file:` URI or a
 *  plain path, `Folder.path` the directory's path. */
class LocalFileSource(private val extensions: Set<String> = DEFAULT_EXTENSIONS) : FileSource {
    override suspend fun walk(folder: Folder): Walk {
        val root = File(folder.path)
        if (!root.isDirectory) throw FileNotFoundException(folder.path)
        val files = mutableListOf<ScannedFile>()
        val unreadable = mutableListOf<String>()
        root.walkTopDown()
            .onEnter { !it.name.startsWith(".") || it == root }
            .onFail { dir, _ -> unreadable.add(dir.path) }
            .forEach { file ->
                if (file.isFile && isAudioFileName(file.name, extensions)) {
                    files.add(ScannedFile(file.toURI().toString(), file.path, file.length(), file.lastModified()))
                }
            }
        return Walk(files.sortedBy { it.path }, unreadable)
    }

    companion object {
        fun folder(directory: File): Folder = Folder(directory.toURI().toString(), directory.path, directory.name)
    }
}
