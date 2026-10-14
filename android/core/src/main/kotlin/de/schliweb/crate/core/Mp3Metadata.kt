// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

import java.nio.charset.Charset

/**
 * Tags and duration of an MP3, read directly: the ID3v2 tag at the
 * front (versions 2.2, 2.3 and 2.4, with unsynchronisation and extended
 * headers), the ID3v1 tag at the back as a fallback, and the duration
 * from the Xing/Info or VBRI header of the first frame, or from the
 * bitrate and file size for a plain CBR file. Reads a few kilobytes
 * where the platform's retriever parses the whole file.
 */
object Mp3Metadata {
    /** Bytes read from the front; a tag bigger than this is read in a
     *  second step. */
    private const val HEAD = 64 * 1024

    fun read(source: ByteSource): ParsedTags {
        var head = source.read(0, HEAD)
        var fields = Id3v2.Tag()
        var tagSize = 0
        if (head.size >= 10 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
            tagSize = 10 + Id3v2.syncsafe(head, 6)
            if (head.u8(5) and 0x10 != 0) tagSize += 10 // footer (v2.4)
            if (tagSize > head.size) head = source.read(0, tagSize + 4096)
            fields = Id3v2.parse(head.copyOfRange(0, minOf(tagSize, head.size)))
        }
        var meta = fields.toTrackMetadata()
        val tail = if (source.size >= 128) source.read(source.size - 128, 128) else ByteArray(0)
        val id3v1 = Id3v1.parse(tail)
        if (id3v1 != null) meta = merge(meta, id3v1)
        val audioEnd = source.size - (if (id3v1 != null) 128 else 0)
        val audio = if (head.size >= tagSize + 4096) head.copyOfRange(tagSize, head.size) else source.read(tagSize.toLong(), 8192)
        meta = meta.copy(duration = duration(audio, audioEnd - tagSize))
        return ParsedTags(meta, fields.pictures)
    }

    /** ID3v1 fills only what v2 left empty; everything else stays as v2
     *  read it (a copy, not a rebuild, so no field is lost as more are
     *  added). */
    private fun merge(primary: TrackMetadata, fallback: TrackMetadata) = primary.copy(
        title = primary.title ?: fallback.title,
        artist = primary.artist ?: fallback.artist,
        album = primary.album ?: fallback.album,
        trackNumber = primary.trackNumber ?: fallback.trackNumber,
        year = primary.year ?: fallback.year,
        genre = primary.genre ?: fallback.genre,
    )

    // MPEG audio frames

    private val BITRATES_V1 = arrayOf(
        intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448), // layer I
        intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384),    // layer II
        intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320),     // layer III
    )
    private val BITRATES_V2 = arrayOf(
        intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256),
        intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160),
        intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160),
    )
    private val SAMPLE_RATES = arrayOf(intArrayOf(44100, 48000, 32000), intArrayOf(22050, 24000, 16000), intArrayOf(11025, 12000, 8000))

    private class Frame(val version: Int, val layer: Int, val bitrate: Int, val sampleRate: Int, val samples: Int, val channels: Int)

    /** Parses the header at [i], or null when it is not one. */
    private fun frame(b: ByteArray, i: Int): Frame? {
        if (i + 4 > b.size || b.u8(i) != 0xff || b.u8(i + 1) and 0xe0 != 0xe0) return null
        val versionBits = b.u8(i + 1) shr 3 and 0x03
        val layerBits = b.u8(i + 1) shr 1 and 0x03
        val bitrateIndex = b.u8(i + 2) shr 4
        val rateIndex = b.u8(i + 2) shr 2 and 0x03
        if (versionBits == 1 || layerBits == 0 || bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) return null
        val version = when (versionBits) { 3 -> 1; 2 -> 2; else -> 3 } // 1 = MPEG-1, 2 = MPEG-2, 3 = MPEG-2.5
        val layer = 4 - layerBits
        val bitrate = (if (version == 1) BITRATES_V1 else BITRATES_V2)[layer - 1][bitrateIndex] * 1000
        val sampleRate = SAMPLE_RATES[version - 1][rateIndex]
        val samples = when (layer) { 1 -> 384; 2 -> 1152; else -> if (version == 1) 1152 else 576 }
        val channels = if (b.u8(i + 3) shr 6 == 3) 1 else 2
        return Frame(version, layer, bitrate, sampleRate, samples, channels)
    }

    /** Seconds, from the first frame found in [audio] (the bytes right
     *  after the tag) and the number of audio bytes in the file. */
    internal fun duration(audio: ByteArray, audioBytes: Long): Double {
        var i = 0
        while (i + 4 <= audio.size) {
            val f = frame(audio, i)
            if (f != null && f.bitrate > 0) {
                // Must be followed by another valid header or be a Xing frame,
                // otherwise this was a stray sync pattern.
                val length = frameLength(f, audio.u8(i + 2) shr 1 and 1)
                val xing = xingFrames(audio, i, f)
                if (xing != null) return xing * f.samples.toDouble() / f.sampleRate
                val vbri = vbriFrames(audio, i)
                if (vbri != null) return vbri * f.samples.toDouble() / f.sampleRate
                if (i + length + 4 <= audio.size && frame(audio, i + length) == null) {
                    i++
                    continue
                }
                return if (audioBytes > 0) audioBytes * 8.0 / f.bitrate else 0.0
            }
            i++
        }
        return 0.0
    }

    private fun frameLength(f: Frame, padding: Int): Int = when (f.layer) {
        1 -> (12 * f.bitrate / f.sampleRate + padding) * 4
        else -> f.samples / 8 * f.bitrate / f.sampleRate + padding
    }

    /** Frame count from a Xing or Info header, which sits after the side
     *  information of the first frame. */
    private fun xingFrames(b: ByteArray, i: Int, f: Frame): Long? {
        val sideInfo = if (f.version == 1) (if (f.channels == 1) 17 else 32) else (if (f.channels == 1) 9 else 17)
        val at = i + 4 + sideInfo
        if (at + 16 > b.size) return null
        val tag = String(b, at, 4, Charsets.ISO_8859_1)
        if (tag != "Xing" && tag != "Info") return null
        val flags = b.u32(at + 4)
        if (flags and 1L == 0L) return null
        val frames = b.u32(at + 8)
        return if (frames > 0) frames else null
    }

    /** VBRI (Fraunhofer) sits 32 bytes after the header. */
    private fun vbriFrames(b: ByteArray, i: Int): Long? {
        val at = i + 4 + 32
        if (at + 18 > b.size || String(b, at, 4, Charsets.ISO_8859_1) != "VBRI") return null
        val frames = b.u32(at + 14)
        return if (frames > 0) frames else null
    }
}

/** The 128-byte tag at the end of old MP3s. */
internal object Id3v1 {
    fun parse(tail: ByteArray): TrackMetadata? {
        if (tail.size != 128 || tail[0] != 'T'.code.toByte() || tail[1] != 'A'.code.toByte() || tail[2] != 'G'.code.toByte()) return null
        fun text(from: Int, length: Int): String? {
            var end = from + length
            while (end > from && (tail[end - 1].toInt() == 0 || tail[end - 1] == ' '.code.toByte())) end--
            return if (end > from) String(tail, from, end - from, Charsets.ISO_8859_1).trim().takeIf { it.isNotEmpty() } else null
        }
        // ID3v1.1: a zero byte before the last comment byte makes that byte the track number.
        val track = if (tail[125].toInt() == 0 && tail[126].toInt() != 0) tail.u8(126) else null
        return TrackMetadata(
            title = text(3, 30),
            artist = text(33, 30),
            album = text(63, 30),
            year = text(93, 4)?.let(TagText::year),
            trackNumber = track,
            genre = Id3v1Genres.name(tail.u8(127)),
        )
    }
}

/** ID3v2 frames, the few that matter. */
internal object Id3v2 {
    class Tag {
        val text = LinkedHashMap<String, String>()
        val pictures = mutableListOf<Picture>()

        fun toTrackMetadata(): TrackMetadata {
            val track = text["TRCK"]?.let(TagText::numberAndTotal)
            val disc = text["TPOS"]?.let(TagText::numberAndTotal)
            return TrackMetadata(
                title = text["TIT2"],
                artist = text["TPE1"],
                albumArtist = text["TPE2"],
                album = text["TALB"],
                trackNumber = track?.first,
                trackTotal = track?.second,
                discNumber = disc?.first,
                discTotal = disc?.second,
                // TDRC (v2.4) and TYER (v2.3) hold the year; TDAT is day and month and never a year.
                year = listOfNotNull(text["TDRC"], text["TYER"], text["TDOR"], text["TORY"]).firstNotNullOfOrNull(TagText::year),
                genre = text["TCON"]?.let(TagText::genre),
                composer = text["TCOM"],
                // TCMP is iTunes' extension; "1" when set.
                compilation = text["TCMP"]?.let(TagText::flag) ?: false,
                artistSort = text["TSOP"],
                albumArtistSort = text["TSO2"],
                albumSort = text["TSOA"],
                trackGain = gains.first,
                albumGain = gains.second,
            )
        }

        /** ReplayGain from TXXX frames, iTunes Sound Check from its COMM
         *  (or TXXX) frame; user frames are keyed "TXXX:name". */
        private val gains: Pair<Double?, Double?>
            get() = ReplayGain.gains(
                track = text["TXXX:replaygain_track_gain"]?.let(ReplayGain::decibels),
                trackPeak = text["TXXX:replaygain_track_peak"]?.let(ReplayGain::peak),
                album = text["TXXX:replaygain_album_gain"]?.let(ReplayGain::decibels),
                albumPeak = text["TXXX:replaygain_album_peak"]?.let(ReplayGain::peak),
                soundCheck = (text["COMM:itunnorm"] ?: text["TXXX:itunnorm"])?.let(ReplayGain::soundCheck),
            )
    }

    /** v2.2 three-letter ids mapped onto their v2.3 names. */
    private val V22 = mapOf(
        "TT2" to "TIT2", "TP1" to "TPE1", "TP2" to "TPE2", "TAL" to "TALB", "TRK" to "TRCK",
        "TPA" to "TPOS", "TYE" to "TYER", "TCO" to "TCON", "TOR" to "TORY", "PIC" to "APIC",
        "TCM" to "TCOM", "TCP" to "TCMP", "TSP" to "TSOP", "TS2" to "TSO2", "TSA" to "TSOA",
    )
    private val WANTED = setOf(
        "TIT2", "TPE1", "TPE2", "TALB", "TRCK", "TPOS", "TYER", "TDRC", "TDOR", "TORY", "TCON", "APIC",
        "TCOM", "TCMP", "TSOP", "TSO2", "TSOA", "TXXX", "COMM",
    )

    fun syncsafe(b: ByteArray, i: Int): Int =
        (b.u8(i) and 0x7f shl 21) or (b.u8(i + 1) and 0x7f shl 14) or (b.u8(i + 2) and 0x7f shl 7) or (b.u8(i + 3) and 0x7f)

    /** [tag] starts with the 10-byte header and holds the whole tag (or
     *  as much of it as was read; frames past the end are dropped). */
    fun parse(tag: ByteArray): Tag {
        val result = Tag()
        if (tag.size < 10) return result
        val version = tag.u8(3)
        val flags = tag.u8(5)
        val unsync = flags and 0x80 != 0
        var body = tag.copyOfRange(10, tag.size)
        if (unsync && version < 4) body = deunsync(body)
        var pos = 0
        if (flags and 0x40 != 0 && version >= 3) {
            // Extended header: v2.3 has a plain size not counting itself, v2.4 a syncsafe one that does.
            if (body.size < 4) return result
            pos = if (version == 3) 4 + body.u32(0).toInt() else syncsafe(body, 0)
        }
        val headerSize = if (version == 2) 6 else 10
        while (pos + headerSize <= body.size) {
            if (body[pos].toInt() == 0) break // padding
            val rawId = String(body, pos, if (version == 2) 3 else 4, Charsets.ISO_8859_1)
            val size = when (version) {
                2 -> body.u24(pos + 3)
                3 -> body.u32(pos + 4).toInt()
                else -> syncsafe(body, pos + 4)
            }
            val frameFlags = if (version == 2) 0 else body.u16(pos + 8)
            pos += headerSize
            if (size < 0 || size > body.size - pos) break
            val id = if (version == 2) V22[rawId] ?: rawId else rawId
            if (id in WANTED) {
                var data = body.copyOfRange(pos, pos + size)
                if (version == 4) {
                    if (frameFlags and 0x01 != 0 && data.size >= 4) data = data.copyOfRange(4, data.size) // data length indicator
                    if (frameFlags and 0x02 != 0 || unsync) data = deunsync(data)
                }
                val compressedOrEncrypted = version == 3 && frameFlags and 0x00c0 != 0 || version == 4 && frameFlags and 0x000c != 0
                if (!compressedOrEncrypted) {
                    when (id) {
                        "APIC" -> picture(data, version)?.let(result.pictures::add)
                        // User frames carry their own name: TXXX a description,
                        // COMM a language and a description. Keyed by both.
                        "TXXX", "COMM" -> userFrame(data, skip = if (id == "COMM") 3 else 0)?.let { (name, value) ->
                            val key = "$id:${name.lowercase()}"
                            if (value.isNotEmpty() && key !in result.text) result.text[key] = value
                        }
                        else -> textFrame(data)?.let { if (id !in result.text) result.text[id] = it }
                    }
                }
            }
            pos += size
        }
        return result
    }

    private fun deunsync(b: ByteArray): ByteArray {
        val out = ByteArray(b.size)
        var n = 0
        var i = 0
        while (i < b.size) {
            out[n++] = b[i]
            if (b[i] == 0xff.toByte() && i + 1 < b.size && b[i + 1].toInt() == 0) i++
            i++
        }
        return out.copyOf(n)
    }

    private fun charset(encoding: Int): Charset = when (encoding) {
        1 -> Charsets.UTF_16 // with BOM
        2 -> Charsets.UTF_16BE
        3 -> Charsets.UTF_8
        else -> Charsets.ISO_8859_1
    }

    /** A TXXX or COMM frame: encoding, [skip] bytes (the COMM language),
     *  the name terminated by one or two zero bytes, then the value. */
    private fun userFrame(data: ByteArray, skip: Int): Pair<String, String>? {
        if (data.size < 2 + skip) return null
        val encoding = data.u8(0)
        val wide = encoding == 1 || encoding == 2
        val from = 1 + skip
        var i = from
        if (wide) {
            while (i + 1 < data.size && !(data[i].toInt() == 0 && data[i + 1].toInt() == 0)) i += 2
        } else {
            while (i < data.size && data[i].toInt() != 0) i++
        }
        val name = String(data, from, (i - from).coerceAtLeast(0), charset(encoding)).trim()
        val valueStart = (i + if (wide) 2 else 1).coerceAtMost(data.size)
        val value = String(data, valueStart, data.size - valueStart, charset(encoding)).trim('\u0000').trim()
        return name to value
    }

    /** The first value of a text frame; v2.4 may hold several, separated
     *  by a zero. */
    private fun textFrame(data: ByteArray): String? {
        if (data.size < 2) return null
        val encoding = data.u8(0)
        val text = String(data, 1, data.size - 1, charset(encoding))
        return text.split('\u0000').firstOrNull { it.isNotBlank() }?.trim()
    }

    private fun picture(data: ByteArray, version: Int): Picture? {
        if (data.size < 4) return null
        val encoding = data.u8(0)
        var pos = 1
        val mime: String
        if (version == 2) {
            mime = when (String(data, 1, 3, Charsets.ISO_8859_1).uppercase()) { "PNG" -> "image/png"; else -> "image/jpeg" }
            pos = 4
        } else {
            val end = indexOf(data, pos, 0) ?: return null
            mime = String(data, pos, end - pos, Charsets.ISO_8859_1)
            pos = end + 1
        }
        if (pos >= data.size) return null
        val type = data.u8(pos)
        pos++
        // Description, terminated by one or two zero bytes depending on the encoding.
        pos = if (encoding == 1 || encoding == 2) {
            var i = pos
            while (i + 1 < data.size && !(data[i].toInt() == 0 && data[i + 1].toInt() == 0)) i += 2
            i + 2
        } else {
            (indexOf(data, pos, 0) ?: return null) + 1
        }
        if (pos >= data.size) return null
        return Picture(type, if (mime.contains('/')) mime else "image/$mime", data.copyOfRange(pos, data.size))
    }

    private fun indexOf(b: ByteArray, from: Int, value: Int): Int? {
        for (i in from until b.size) if (b[i].toInt() == value) return i
        return null
    }
}
