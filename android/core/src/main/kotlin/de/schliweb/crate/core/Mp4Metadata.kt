// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

/**
 * Tags and duration of an MP4 audio file (M4A, M4B): the `moov` atom's
 * `mvhd` for the duration and its `udta/meta/ilst` for the iTunes-style
 * tags. Walks the top-level atoms by size, so a `moov` written after
 * the audio (ffmpeg's default) costs one more read, not a pass over the
 * audio.
 */
object Mp4Metadata {
    class NotMp4 : java.io.IOException("not an MP4 file")

    fun read(source: ByteSource): ParsedTags {
        var offset = 0L
        var seen = 0
        while (offset + 8 <= source.size && seen < 64) {
            val header = source.read(offset, 16)
            if (header.size < 8) break
            var size = header.u32(0)
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            var headerSize = 8
            if (size == 1L && header.size >= 16) {
                size = header.u64(8)
                headerSize = 16
            } else if (size == 0L) {
                size = source.size - offset
            }
            if (seen == 0 && type != "ftyp") throw NotMp4()
            if (size < headerSize) break
            if (type == "moov") {
                val moov = source.read(offset + headerSize, (size - headerSize).coerceAtMost(16L * 1024 * 1024).toInt())
                return parseMoov(moov)
            }
            offset += size
            seen++
        }
        return ParsedTags(TrackMetadata(), emptyList())
    }

    private class Atom(val type: String, val start: Int, val end: Int)

    /** Child atoms of [b] between [from] and [to]. */
    private fun children(b: ByteArray, from: Int, to: Int): List<Atom> {
        val out = mutableListOf<Atom>()
        var pos = from
        while (pos + 8 <= to) {
            var size = b.u32(pos)
            var headerSize = 8
            if (size == 1L && pos + 16 <= to) {
                size = b.u64(pos + 8)
                headerSize = 16
            } else if (size == 0L) {
                size = (to - pos).toLong()
            }
            if (size < headerSize || pos + size > to) break
            out.add(Atom(String(b, pos + 4, 4, Charsets.ISO_8859_1), pos + headerSize, (pos + size).toInt()))
            pos += size.toInt()
        }
        return out
    }

    private fun parseMoov(moov: ByteArray): ParsedTags {
        var duration = 0.0
        val text = LinkedHashMap<String, String>()
        val pictures = mutableListOf<Picture>()
        var track: Pair<Int?, Int?>? = null
        var disc: Pair<Int?, Int?>? = null
        var genreIndex: Int? = null
        for (atom in children(moov, 0, moov.size)) {
            when (atom.type) {
                "mvhd" -> duration = mvhdDuration(moov, atom)
                "udta" -> for (meta in children(moov, atom.start, atom.end)) {
                    if (meta.type != "meta") continue
                    // meta is a full atom: version and flags precede its children.
                    for (ilst in children(moov, meta.start + 4, meta.end)) {
                        if (ilst.type != "ilst") continue
                        for (item in children(moov, ilst.start, ilst.end)) {
                            for (data in children(moov, item.start, item.end)) {
                                if (data.type != "data" || data.end - data.start < 8) continue
                                val kind = moov.u32(data.start).toInt() and 0xffffff
                                val payloadStart = data.start + 8
                                val payload = moov.copyOfRange(payloadStart, data.end)
                                when (item.type) {
                                    "trkn" -> if (payload.size >= 6) track = nonZero(payload.u16(2)) to nonZero(payload.u16(4))
                                    "disk" -> if (payload.size >= 6) disc = nonZero(payload.u16(2)) to nonZero(payload.u16(4))
                                    "gnre" -> if (payload.size >= 2) genreIndex = payload.u16(0)
                                    "covr" -> if (payload.isNotEmpty()) {
                                        pictures.add(Picture(3, if (kind == 14) "image/png" else "image/jpeg", payload))
                                    }
                                    else -> if (kind == 1 && item.type !in text) {
                                        val value = String(payload, Charsets.UTF_8).trim()
                                        if (value.isNotEmpty()) text[item.type] = value
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        val metadata = TrackMetadata(
            title = text["©nam"],
            artist = text["©ART"],
            albumArtist = text["aART"],
            album = text["©alb"],
            trackNumber = track?.first,
            trackTotal = track?.second,
            discNumber = disc?.first,
            discTotal = disc?.second,
            year = text["©day"]?.let(TagText::year),
            // The binary gnre atom is 1-based into the ID3v1 list; the text
            // form may still carry "(17)".
            genre = text["©gen"]?.let(TagText::genre) ?: genreIndex?.let { Id3v1Genres.name(it - 1) },
            duration = duration,
        )
        return ParsedTags(metadata, pictures)
    }

    private fun nonZero(n: Int): Int? = if (n == 0) null else n

    private fun mvhdDuration(b: ByteArray, atom: Atom): Double {
        val version = b.u8(atom.start)
        return if (version == 1) {
            if (atom.end - atom.start < 32) return 0.0
            val timescale = b.u32(atom.start + 20)
            val duration = b.u64(atom.start + 24)
            if (timescale > 0) duration.toDouble() / timescale else 0.0
        } else {
            if (atom.end - atom.start < 24) return 0.0
            val timescale = b.u32(atom.start + 12)
            val duration = b.u32(atom.start + 16)
            if (timescale > 0) duration.toDouble() / timescale else 0.0
        }
    }
}
