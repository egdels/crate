// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import de.schliweb.crate.core.FlacMetadata
import de.schliweb.crate.core.Lyrics
import de.schliweb.crate.core.LyricsSource
import de.schliweb.crate.core.Mp3Metadata
import de.schliweb.crate.core.Mp4Metadata
import de.schliweb.crate.core.Paths
import de.schliweb.crate.core.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lyrics from next to the file first, then from its tags: an `.lrc`
 * (timed) or `.txt` (plain) with the track's name beside it or in a
 * `lyrics` subfolder, then a LYRICS comment (FLAC), a USLT frame (MP3)
 * or a ©lyr atom (M4A).
 *
 * The sidecar is found without a directory query: a tree document's id
 * is a path ("primary:Music/Artist/Album/01.mp3") on the storage
 * provider and on most others, so the sibling's id is that path with
 * the name swapped, and opening it either works or throws.
 */
class SafLyricsSource(private val context: Context) : LyricsSource {
    override suspend fun lyrics(track: Track): Lyrics? = withContext(Dispatchers.IO) {
        sidecar(track)?.let { Lyrics.parse(it) }?.takeIf { !it.isEmpty }
            ?: tagged(track)?.let { Lyrics.parse(it) }?.takeIf { !it.isEmpty }
    }

    /** `.lrc` before `.txt`, beside the file before the subfolder. */
    private fun sidecar(track: Track): String? {
        val uri = Uri.parse(track.uri)
        val base = track.path.substringAfterLast('/').substringBeforeLast('.')
        val names = listOf("lrc", "txt").flatMap { ext -> listOf("$base.$ext", "lyrics/$base.$ext", "Lyrics/$base.$ext") }
        if (uri.scheme == "file") {
            val folder = File(uri.path ?: return null).parentFile ?: return null
            for (name in names) {
                val file = File(folder, name)
                if (file.isFile) return file.readText()
            }
            return null
        }
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val parent = documentId.substringBeforeLast('/', "")
        for (name in names) {
            val id = if (parent.isEmpty()) name else "$parent/$name"
            val text = runCatching {
                val sibling = DocumentsContract.buildDocumentUriUsingTree(uri, id)
                context.contentResolver.openInputStream(sibling)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
            if (text != null) return text
        }
        return null
    }

    private fun tagged(track: Track): String? {
        val uri = Uri.parse(track.uri)
        return runCatching {
            when (Paths.extension(track.path)) {
                "flac" -> context.contentResolver.openInputStream(uri)?.use { stream ->
                    val flac = FlacMetadata.read(stream)
                    flac.value("LYRICS") ?: flac.value("UNSYNCEDLYRICS") ?: flac.value("UNSYNCED LYRICS")
                }
                "mp3" -> ContentByteSource(context, uri).use { Mp3Metadata.read(it).lyrics }
                "m4a", "m4b" -> ContentByteSource(context, uri).use { Mp4Metadata.read(it).lyrics }
                else -> null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
