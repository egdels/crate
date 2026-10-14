// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.library

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import de.schliweb.crate.core.ByteSource
import de.schliweb.crate.core.FlacMetadata
import de.schliweb.crate.core.MetadataReader
import de.schliweb.crate.core.Mp3Metadata
import de.schliweb.crate.core.Mp4Metadata
import de.schliweb.crate.core.ParsedTags
import de.schliweb.crate.core.Paths
import de.schliweb.crate.core.ScannedFile
import de.schliweb.crate.core.TagText
import de.schliweb.crate.core.TrackMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Tag reader. MP3, M4A/M4B and FLAC are parsed by Crate itself
 * ([Mp3Metadata], [Mp4Metadata], [FlacMetadata]): a few positional reads
 * through the document's file descriptor, where the platform's
 * `MediaMetadataRetriever` opens the whole file through the media
 * extractor, which measured four to five times slower per file. The
 * retriever remains for everything else (Ogg, Opus, WAV, AAC streams)
 * and as the fallback when a parser finds nothing usable.
 */
class AndroidMetadataReader(private val context: Context) : MetadataReader {
    override suspend fun read(file: ScannedFile): TrackMetadata = withContext(Dispatchers.IO) {
        // An empty file (an interrupted copy) is unreadable without asking
        // anyone; it would otherwise cost a round trip on every rescan.
        if (file.fileSize == 0L) throw IOException("empty file: ${file.path}")
        val uri = Uri.parse(file.uri)
        when (Paths.extension(file.path)) {
            "flac" -> {
                val stream = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(file.path)
                return@withContext FlacMetadata.read(stream).toTrackMetadata()
            }
            "mp3" -> parsed(uri, Mp3Metadata::read)?.let { return@withContext it }
            "m4a", "m4b" -> parsed(uri, Mp4Metadata::read)?.let { return@withContext it }
        }
        retrieve(uri)
    }

    /** The parser's result, or null when it failed or found neither a
     *  title nor a duration, which is when the retriever gets its turn. */
    private fun parsed(uri: Uri, parser: (ByteSource) -> ParsedTags): TrackMetadata? {
        val meta = try {
            ContentByteSource(context, uri).use { parser(it).metadata }
        } catch (e: FileNotFoundException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        return if (meta.title != null || meta.duration > 0) meta else null
    }

    private fun retrieve(uri: Uri): TrackMetadata {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            fun text(key: Int): String? = retriever.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
            val track = text(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.let(TagText::numberAndTotal)
            val disc = text(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)?.let(TagText::numberAndTotal)
            return TrackMetadata(
                title = text(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = text(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                albumArtist = text(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                album = text(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                trackNumber = track?.first,
                trackTotal = track?.second ?: text(MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS)?.let(TagText::int),
                discNumber = disc?.first,
                discTotal = disc?.second,
                // YEAR is the tag; DATE is the container's timestamp, a last resort.
                year = listOfNotNull(text(MediaMetadataRetriever.METADATA_KEY_YEAR), text(MediaMetadataRetriever.METADATA_KEY_DATE))
                    .firstNotNullOfOrNull(TagText::year),
                genre = text(MediaMetadataRetriever.METADATA_KEY_GENRE)?.let(TagText::genre),
                composer = text(MediaMetadataRetriever.METADATA_KEY_COMPOSER),
                compilation = text(MediaMetadataRetriever.METADATA_KEY_COMPILATION)?.let(TagText::flag) ?: false,
                duration = text(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { it / 1000.0 } ?: 0.0,
            )
        } finally {
            runCatching { retriever.release() }
        }
    }
}
