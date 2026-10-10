// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.library

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import de.schliweb.crate.core.FlacMetadata
import de.schliweb.crate.core.MetadataReader
import de.schliweb.crate.core.Paths
import de.schliweb.crate.core.ScannedFile
import de.schliweb.crate.core.TagText
import de.schliweb.crate.core.TrackMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/**
 * Tag reader on top of the platform's `MediaMetadataRetriever`, which
 * understands ID3, iTunes/MP4 and Vorbis comments in Ogg. FLAC goes
 * through Crate's own parser instead, like on the Mac: it reads a few
 * kilobytes where the retriever opens the whole file, and the result
 * does not depend on the device's media framework.
 */
class AndroidMetadataReader(private val context: Context) : MetadataReader {
    override suspend fun read(file: ScannedFile): TrackMetadata = withContext(Dispatchers.IO) {
        val uri = Uri.parse(file.uri)
        if (Paths.extension(file.path) == "flac") {
            val stream = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(file.path)
            return@withContext FlacMetadata.read(stream).toTrackMetadata()
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            fun text(key: Int): String? = retriever.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
            val track = text(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.let(TagText::numberAndTotal)
            val disc = text(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)?.let(TagText::numberAndTotal)
            TrackMetadata(
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
                duration = text(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { it / 1000.0 } ?: 0.0,
            )
        } finally {
            runCatching { retriever.release() }
        }
    }
}
