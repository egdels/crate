// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * The metadata blocks at the front of a FLAC file: STREAMINFO for the
 * duration, VORBIS_COMMENT for the tags, PICTURE for the cover. Read
 * directly from the stream, like the Mac app does: it is a few
 * kilobytes where the platform's retriever opens the whole file, and the
 * result is the same on every Android version. Audio frames are never
 * touched.
 */
class FlacMetadata private constructor() {
    class Picture(
        /** 3 is the front cover, per the FLAC specification. */
        val type: Int,
        val mimeType: String,
        val data: ByteArray,
    )

    class NotFlac : IOException("not a FLAC file")

    /** Keys uppercased, in file order; a key may repeat. */
    val comments: MutableList<Pair<String, String>> = mutableListOf()
    var duration: Double = 0.0
        private set
    val pictures: MutableList<Picture> = mutableListOf()

    /** First value for the key, case-insensitively. */
    fun value(key: String): String? {
        val wanted = key.uppercase()
        return comments.firstOrNull { it.first == wanted }?.second.trimmedOrNull()
    }

    /** The front cover if there is one, otherwise the first picture. */
    val cover: Picture? get() = pictures.firstOrNull { it.type == 3 } ?: pictures.firstOrNull()

    /** Vorbis comment keys as the common taggers write them. */
    fun toTrackMetadata(): TrackMetadata {
        val gains = ReplayGain.gains(
            track = value("REPLAYGAIN_TRACK_GAIN")?.let(ReplayGain::decibels),
            trackPeak = value("REPLAYGAIN_TRACK_PEAK")?.let(ReplayGain::peak),
            album = value("REPLAYGAIN_ALBUM_GAIN")?.let(ReplayGain::decibels),
            albumPeak = value("REPLAYGAIN_ALBUM_PEAK")?.let(ReplayGain::peak),
            soundCheck = value("ITUNNORM")?.let(ReplayGain::soundCheck),
        )
        val track = value("TRACKNUMBER")?.let(TagText::numberAndTotal)
        val disc = value("DISCNUMBER")?.let(TagText::numberAndTotal)
        return TrackMetadata(
            title = value("TITLE"),
            artist = value("ARTIST") ?: value("PERFORMER"),
            albumArtist = value("ALBUMARTIST") ?: value("ALBUM ARTIST") ?: value("ALBUM_ARTIST"),
            album = value("ALBUM"),
            trackNumber = track?.first,
            trackTotal = track?.second ?: value("TRACKTOTAL")?.let(TagText::int) ?: value("TOTALTRACKS")?.let(TagText::int),
            discNumber = disc?.first,
            discTotal = disc?.second ?: value("DISCTOTAL")?.let(TagText::int) ?: value("TOTALDISCS")?.let(TagText::int),
            year = listOfNotNull(value("DATE"), value("YEAR"), value("ORIGINALDATE")).firstNotNullOfOrNull(TagText::year),
            genre = value("GENRE")?.let(TagText::genre),
            composer = value("COMPOSER"),
            compilation = value("COMPILATION")?.let(TagText::flag) ?: false,
            artistSort = value("ARTISTSORT"),
            albumArtistSort = value("ALBUMARTISTSORT"),
            albumSort = value("ALBUMSORT"),
            trackGain = gains.first,
            albumGain = gains.second,
            duration = duration,
        )
    }

    companion object {
        /** Reads the blocks and closes the stream. Throws [NotFlac] when
         *  the marker is missing. */
        fun read(stream: InputStream): FlacMetadata = DataInputStream(stream.buffered()).use { input ->
            val marker = ByteArray(4)
            try {
                input.readFully(marker)
            } catch (e: EOFException) {
                throw NotFlac()
            }
            if (!marker.contentEquals("fLaC".toByteArray())) throw NotFlac()
            val meta = FlacMetadata()
            var last = false
            while (!last) {
                val header = ByteArray(4)
                try {
                    input.readFully(header)
                } catch (e: EOFException) {
                    break
                }
                last = header[0].toInt() and 0x80 != 0
                val type = header[0].toInt() and 0x7f
                val length = (header[1].toInt() and 0xff shl 16) or (header[2].toInt() and 0xff shl 8) or (header[3].toInt() and 0xff)
                when (type) {
                    0, 4, 6 -> {
                        val block = ByteArray(length)
                        try {
                            input.readFully(block)
                        } catch (e: EOFException) {
                            break
                        }
                        when (type) {
                            0 -> meta.duration = streamInfoDuration(block)
                            4 -> meta.comments.addAll(vorbisComments(block))
                            else -> picture(block)?.let(meta.pictures::add)
                        }
                    }
                    else -> input.skipFully(length)
                }
            }
            meta
        }

        private fun DataInputStream.skipFully(count: Int) {
            var left = count.toLong()
            while (left > 0) {
                val skipped = skip(left)
                if (skipped <= 0) {
                    if (read() < 0) return
                    left--
                } else {
                    left -= skipped
                }
            }
        }

        /** Sample rate (20 bits) and total samples (36 bits) sit at byte 10. */
        internal fun streamInfoDuration(block: ByteArray): Double {
            if (block.size < 18) return 0.0
            fun b(i: Int) = block[i].toLong() and 0xff
            val sampleRate = (b(10) shl 12) or (b(11) shl 4) or (b(12) shr 4)
            val totalSamples = ((b(13) and 0x0f) shl 32) or (b(14) shl 24) or (b(15) shl 16) or (b(16) shl 8) or b(17)
            return if (sampleRate > 0) totalSamples.toDouble() / sampleRate.toDouble() else 0.0
        }

        /** Little-endian lengths, UTF-8 "KEY=value" entries. */
        internal fun vorbisComments(block: ByteArray): List<Pair<String, String>> {
            val reader = Reader(block)
            val vendorLength = reader.uint32LE() ?: return emptyList()
            if (!reader.skip(vendorLength)) return emptyList()
            val count = reader.uint32LE() ?: return emptyList()
            val comments = mutableListOf<Pair<String, String>>()
            for (i in 0 until count) {
                val length = reader.uint32LE() ?: break
                val entry = reader.string(length) ?: break
                val equals = entry.indexOf('=')
                if (equals < 0) continue
                comments.add(entry.substring(0, equals).uppercase() to entry.substring(equals + 1))
            }
            return comments
        }

        /** Big-endian: type, mime, description, four dimension fields, data. */
        internal fun picture(block: ByteArray): Picture? {
            val reader = Reader(block)
            val type = reader.uint32BE() ?: return null
            val mimeLength = reader.uint32BE() ?: return null
            val mime = reader.string(mimeLength) ?: return null
            val descriptionLength = reader.uint32BE() ?: return null
            if (!reader.skip(descriptionLength) || !reader.skip(16)) return null
            val dataLength = reader.uint32BE() ?: return null
            val data = reader.bytes(dataLength) ?: return null
            if (data.isEmpty()) return null
            return Picture(type, mime, data)
        }

        private class Reader(private val data: ByteArray) {
            private var offset = 0

            fun bytes(count: Int): ByteArray? {
                if (count < 0 || offset + count > data.size) return null
                val out = data.copyOfRange(offset, offset + count)
                offset += count
                return out
            }

            fun skip(count: Int): Boolean {
                if (count < 0 || offset + count > data.size) return false
                offset += count
                return true
            }

            fun string(count: Int): String? = bytes(count)?.toString(Charsets.UTF_8)

            fun uint32LE(): Int? {
                val b = bytes(4) ?: return null
                val value = (b[0].toLong() and 0xff) or ((b[1].toLong() and 0xff) shl 8) or
                    ((b[2].toLong() and 0xff) shl 16) or ((b[3].toLong() and 0xff) shl 24)
                return if (value > Int.MAX_VALUE) null else value.toInt()
            }

            fun uint32BE(): Int? {
                val b = bytes(4) ?: return null
                val value = ((b[0].toLong() and 0xff) shl 24) or ((b[1].toLong() and 0xff) shl 16) or
                    ((b[2].toLong() and 0xff) shl 8) or (b[3].toLong() and 0xff)
                return if (value > Int.MAX_VALUE) null else value.toInt()
            }
        }
    }
}
