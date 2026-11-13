// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.linux

import de.schliweb.crate.core.FileByteSource
import de.schliweb.crate.core.FlacMetadata
import de.schliweb.crate.core.MetadataReader
import de.schliweb.crate.core.Mp3Metadata
import de.schliweb.crate.core.Mp4Metadata
import de.schliweb.crate.core.Paths
import de.schliweb.crate.core.ReplayGain
import de.schliweb.crate.core.ScannedFile
import de.schliweb.crate.core.TagText
import de.schliweb.crate.core.TrackMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.freedesktop.gstreamer.Format
import org.freedesktop.gstreamer.State
import org.freedesktop.gstreamer.TagList
import org.freedesktop.gstreamer.elements.PlayBin
import org.freedesktop.gstreamer.ElementFactory
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Tag reader. MP3, M4A/M4B and FLAC go through Crate's own parsers, as on
 * Android; everything else (Ogg, Opus, WAV, raw AAC) and a file the
 * parsers find nothing in is prerolled by a GStreamer playbin with fake
 * sinks, whose merged audio tags and duration are what GStreamer knows.
 */
class LinuxMetadataReader : MetadataReader {
    /** Prerolling a pipeline costs threads and a decoder each; the parsers
     *  above are cheap and need no limit. */
    private val pipelines = Semaphore(4)

    override suspend fun read(file: ScannedFile): TrackMetadata = withContext(Dispatchers.IO) {
        if (file.fileSize == 0L) throw IOException("empty file: ${file.path}")
        parsed(File(file.path))?.takeIf { it.title != null || it.duration > 0 }?.let { return@withContext it }
        pipelines.withPermit { discover(file.uri) }
    }

    private fun discover(uri: String): TrackMetadata {
        val playbin = PlayBin("probe", URI(uri))
        try {
            playbin.setAudioSink(ElementFactory.make("fakesink", null))
            playbin.setVideoSink(ElementFactory.make("fakesink", null))
            playbin.setState(State.PAUSED)
            if (playbin.getState(10, TimeUnit.SECONDS) != State.PAUSED) throw IOException("cannot preroll $uri")
            val duration = playbin.queryDuration(Format.TIME).takeIf { it > 0 }?.let { it / 1e9 } ?: 0.0
            val tags = if (playbin.nAudio > 0) playbin.getAudioTags(0) else null
            return tags?.let { metadata(it, duration) } ?: TrackMetadata(duration = duration)
        } finally {
            playbin.setState(State.NULL)
            playbin.dispose()
        }
    }

    private fun metadata(tags: TagList, duration: Double): TrackMetadata {
        fun text(name: String): String? =
            runCatching { tags.getString(name, 0) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        fun number(name: String): Number? =
            if (tags.getValueCount(name) > 0) runCatching { tags.getNumber(name, 0) }.getOrNull() else null
        // Dates come as GstDateTime or GDate, which the binding cannot
        // always map; the year is the leading digits of either's string.
        val year = listOf("datetime", "date").firstNotNullOfOrNull { name ->
            if (tags.getValueCount(name) == 0) null
            else runCatching { tags.getValue(name, 0)?.toString() }.getOrNull()?.let(TagText::year)
        }
        val (trackGain, albumGain) = ReplayGain.gains(
            number("replaygain-track-gain")?.toDouble(), number("replaygain-track-peak")?.toDouble(),
            number("replaygain-album-gain")?.toDouble(), number("replaygain-album-peak")?.toDouble(), null,
        )
        return TrackMetadata(
            title = text("title"),
            artist = text("artist"),
            albumArtist = text("album-artist"),
            album = text("album"),
            trackNumber = number("track-number")?.toInt()?.takeIf { it > 0 },
            trackTotal = number("track-count")?.toInt()?.takeIf { it > 0 },
            discNumber = number("album-disc-number")?.toInt()?.takeIf { it > 0 },
            discTotal = number("album-disc-count")?.toInt()?.takeIf { it > 0 },
            year = year,
            genre = text("genre")?.let(TagText::genre),
            composer = text("composer"),
            artistSort = text("artist-sortname"),
            albumArtistSort = text("album-artist-sortname"),
            albumSort = text("album-sortname"),
            trackGain = trackGain,
            albumGain = albumGain,
            duration = duration,
        )
    }

    companion object {
        /** What Crate's own parsers find in the file, or null for formats
         *  they do not read or a file they fail on. */
        fun parsed(file: File): TrackMetadata? = runCatching {
            when (Paths.extension(file.name)) {
                "flac" -> FileInputStream(file).use { FlacMetadata.read(it).toTrackMetadata() }
                "mp3" -> FileByteSource(file).use(Mp3Metadata::read).metadata
                "m4a", "m4b" -> FileByteSource(file).use(Mp4Metadata::read).metadata
                else -> null
            }
        }.getOrNull()

        /** The embedded cover's bytes, front cover first. */
        fun cover(file: File): ByteArray? = runCatching {
            when (Paths.extension(file.name)) {
                "flac" -> FileInputStream(file).use { FlacMetadata.read(it).cover?.data }
                "mp3" -> FileByteSource(file).use(Mp3Metadata::read).cover?.data
                "m4a", "m4b" -> FileByteSource(file).use(Mp4Metadata::read).cover?.data
                else -> null
            }
        }.getOrNull()
    }
}
