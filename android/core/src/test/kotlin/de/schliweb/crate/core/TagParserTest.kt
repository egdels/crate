// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A [ByteSource] over an array, for parser tests. */
class ArrayByteSource(private val bytes: ByteArray) : ByteSource {
    override val size: Long get() = bytes.size.toLong()
    override fun read(offset: Long, length: Int): ByteArray {
        if (offset >= bytes.size || length <= 0) return ByteArray(0)
        return bytes.copyOfRange(offset.toInt(), minOf(bytes.size.toLong(), offset + length).toInt())
    }
    override fun close() {}
}

private fun be32(n: Int) = byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
private fun syncsafe(n: Int) = byteArrayOf((n shr 21 and 0x7f).toByte(), (n shr 14 and 0x7f).toByte(), (n shr 7 and 0x7f).toByte(), (n and 0x7f).toByte())

/** An ID3v2 tag with the given frames (id to raw frame body). */
private fun id3v2(version: Int, frames: List<Pair<String, ByteArray>>, flags: Int = 0): ByteArray {
    val body = ByteArrayOutputStream()
    for ((id, data) in frames) {
        body.write(id.toByteArray(Charsets.ISO_8859_1))
        when (version) {
            2 -> body.write(byteArrayOf((data.size shr 16).toByte(), (data.size shr 8).toByte(), data.size.toByte()))
            3 -> { body.write(be32(data.size)); body.write(byteArrayOf(0, 0)) }
            else -> { body.write(syncsafe(data.size)); body.write(byteArrayOf(0, 0)) }
        }
        body.write(data)
    }
    body.write(ByteArray(16)) // padding
    val out = ByteArrayOutputStream()
    out.write("ID3".toByteArray()); out.write(version); out.write(0); out.write(flags)
    out.write(syncsafe(body.size())); out.write(body.toByteArray())
    return out.toByteArray()
}

private fun text(encoding: Int, value: String): ByteArray {
    val bytes = when (encoding) {
        1 -> byteArrayOf(0xff.toByte(), 0xfe.toByte()) + value.toByteArray(Charsets.UTF_16LE)
        3 -> value.toByteArray(Charsets.UTF_8)
        else -> value.toByteArray(Charsets.ISO_8859_1)
    }
    return byteArrayOf(encoding.toByte()) + bytes
}

/** One MPEG-1 layer III 128 kbit/s 44.1 kHz stereo frame header, followed
 *  by a Xing header claiming [frames] frames when given. */
private fun mp3Frame(frames: Int? = null): ByteArray {
    val frame = ByteArray(417)
    frame[0] = 0xff.toByte(); frame[1] = 0xfb.toByte(); frame[2] = 0x90.toByte(); frame[3] = 0x00
    if (frames != null) {
        val at = 4 + 32
        "Xing".toByteArray().copyInto(frame, at)
        be32(1).copyInto(frame, at + 4)
        be32(frames).copyInto(frame, at + 8)
    }
    return frame
}

class TagParserTest {
    @Test
    fun id3v23WithUtf16AndPicture() {
        val png = ByteArray(32) { it.toByte() }
        val apic = byteArrayOf(0) + "image/png".toByteArray() + byteArrayOf(0, 3) + "front".toByteArray() + byteArrayOf(0) + png
        val tag = id3v2(3, listOf(
            "TIT2" to text(1, "Señorita"), "TPE1" to text(0, "Río Trio"), "TALB" to text(3, "Costa"),
            "TRCK" to text(0, "3/12"), "TPOS" to text(0, "1"), "TYER" to text(0, "1997"), "TDAT" to text(0, "0805"),
            "TCON" to text(0, "(17)"), "TPE2" to text(0, "Band"), "APIC" to apic,
        ))
        val audio = ByteArray(100) + mp3Frame(frames = 1000) + ByteArray(2000)
        val parsed = Mp3Metadata.read(ArrayByteSource(tag + audio))
        val meta = parsed.metadata
        assertEquals("Señorita", meta.title)
        assertEquals("Río Trio", meta.artist)
        assertEquals("Costa", meta.album)
        assertEquals("Band", meta.albumArtist)
        assertEquals(3, meta.trackNumber)
        assertEquals(12, meta.trackTotal)
        assertEquals(1, meta.discNumber)
        assertEquals(1997, meta.year)
        assertEquals("Rock", meta.genre)
        assertEquals(1000 * 1152.0 / 44100, meta.duration, 0.001)
        val cover = assertNotNull(parsed.cover)
        assertEquals("image/png", cover.mimeType)
        assertContentEquals(png, cover.data)
    }

    @Test
    fun userFramesCarryReplayGainAndSoundCheck() {
        fun txxx(encoding: Int, name: String, value: String) =
            "TXXX" to (text(encoding, name) + (if (encoding == 1) byteArrayOf(0, 0) else byteArrayOf(0)) + text(encoding, value).drop(1).toByteArray())
        val comm = "COMM" to (byteArrayOf(0) + "eng".toByteArray() + "iTunNORM".toByteArray() + byteArrayOf(0) +
            " 00001070 0000120F 00008A1B 000082AF 00023568 000234CB 00008000 00007E5F 00000000 00000000".toByteArray())
        val plainComment = "COMM" to (byteArrayOf(0) + "eng".toByteArray() + byteArrayOf(0) + "ripped".toByteArray())
        val tag = id3v2(3, listOf(
            "TIT2" to text(0, "Song"), txxx(0, "replaygain_track_gain", "+9.00 dB"), txxx(1, "REPLAYGAIN_TRACK_PEAK", "0.5"),
            txxx(0, "replaygain_album_gain", "-7.25 dB"), plainComment, comm,
        ))
        val meta = Mp3Metadata.read(ArrayByteSource(tag + mp3Frame())).metadata
        assertEquals("Song", meta.title)
        assertTrue(kotlin.math.abs(meta.trackGain!! - 6.02) < 0.01, "limited by the peak, the UTF-16 frame read too")
        assertEquals(-7.25, meta.albumGain)

        // Sound Check alone: the track gain.
        val checkOnly = id3v2(3, listOf("TIT2" to text(0, "Song"), plainComment, comm))
        val checked = Mp3Metadata.read(ArrayByteSource(checkOnly + mp3Frame())).metadata
        assertTrue(kotlin.math.abs(checked.trackGain!! - (-10 * kotlin.math.log10(4623.0 / 1000))) < 0.001)
        assertNull(checked.albumGain)
    }

    @Test
    fun id3v24SyncsafeAndTdrc() {
        val tag = id3v2(4, listOf("TIT2" to text(3, "Later"), "TDRC" to text(3, "2010-05-01T12:00"), "TPE1" to text(3, "A\u0000B")))
        val frames = (1..10).fold(ByteArray(0)) { acc, _ -> acc + mp3Frame() }
        val meta = Mp3Metadata.read(ArrayByteSource(tag + frames)).metadata
        assertEquals("Later", meta.title)
        assertEquals(2010, meta.year)
        assertEquals("A", meta.artist, "first of several values")
        // CBR estimate: 10 frames of 417 bytes at 128 kbit/s.
        assertEquals(4170 * 8.0 / 128000, meta.duration, 0.001)
    }

    @Test
    fun id3v22ThreeLetterFrames() {
        val tag = id3v2(2, listOf("TT2" to text(0, "Old"), "TP1" to text(0, "Band"), "TRK" to text(0, "7")))
        val meta = Mp3Metadata.read(ArrayByteSource(tag + mp3Frame())).metadata
        assertEquals("Old", meta.title)
        assertEquals("Band", meta.artist)
        assertEquals(7, meta.trackNumber)
    }

    @Test
    fun unsynchronisedTagIsRestored() {
        // A frame body with 0xFF 0x00 pairs inserted by unsynchronisation.
        val raw = id3v2(3, listOf("TIT2" to text(0, "Sync")), flags = 0x80)
        val meta = Mp3Metadata.read(ArrayByteSource(raw + mp3Frame())).metadata
        assertEquals("Sync", meta.title)
    }

    @Test
    fun id3v1FillsWhatV2Lacks() {
        val v1 = ByteArray(128)
        "TAG".toByteArray().copyInto(v1, 0)
        "Old Title".toByteArray().copyInto(v1, 3)
        "Old Artist".toByteArray().copyInto(v1, 33)
        "Old Album".toByteArray().copyInto(v1, 63)
        "1988".toByteArray().copyInto(v1, 93)
        v1[126] = 5; v1[127] = 17
        val meta = Mp3Metadata.read(ArrayByteSource(mp3Frame() + v1)).metadata
        assertEquals("Old Title", meta.title)
        assertEquals("Old Artist", meta.artist)
        assertEquals(1988, meta.year)
        assertEquals(5, meta.trackNumber)
        assertEquals("Rock", meta.genre)

        val withV2 = id3v2(3, listOf(
            "TIT2" to text(0, "New Title"), "TCOM" to text(0, "Composer"), "TCMP" to text(0, "1"), "TSOP" to text(0, "Artist, Old"),
            "COMM" to (byteArrayOf(0) + "eng".toByteArray() + "iTunNORM".toByteArray() + byteArrayOf(0) + " 00001070 00001070".toByteArray()),
        ))
        val merged = Mp3Metadata.read(ArrayByteSource(withV2 + mp3Frame() + v1)).metadata
        assertEquals("New Title", merged.title)
        assertEquals("Old Artist", merged.artist)
        // The v1 tail fills gaps only; nothing v2 read is lost in the merge.
        assertEquals("Composer", merged.composer)
        assertTrue(merged.compilation)
        assertEquals("Artist, Old", merged.artistSort)
        assertTrue(kotlin.math.abs(merged.trackGain!! - (-10 * kotlin.math.log10(4.208))) < 0.001)
    }

    @Test
    fun untaggedMp3HasNoTitleButADuration() {
        val meta = Mp3Metadata.read(ArrayByteSource(mp3Frame(frames = 2000))).metadata
        assertNull(meta.title)
        assertTrue(meta.duration > 50)
    }

    // MP4

    private fun atom(type: String, vararg payload: ByteArray): ByteArray {
        val body = payload.fold(ByteArray(0)) { a, b -> a + b }
        return be32(8 + body.size) + type.toByteArray(Charsets.ISO_8859_1) + body
    }

    private fun data(kind: Int, payload: ByteArray) = atom("data", be32(kind), be32(0), payload)

    private fun mp4(moovFirst: Boolean, vararg items: ByteArray): ByteArray {
        val mvhd = atom("mvhd", ByteArray(12), be32(1000), be32(90_500), ByteArray(80))
        val ilst = atom("ilst", *items)
        val meta = atom("meta", ByteArray(4), ilst)
        val moov = atom("moov", mvhd, atom("udta", meta))
        val ftyp = atom("ftyp", "M4A ".toByteArray(), be32(0))
        val mdat = atom("mdat", ByteArray(5000))
        return if (moovFirst) ftyp + moov + mdat else ftyp + mdat + moov
    }

    @Test
    fun mp4TagsAndDurationWithMoovAtEitherEnd() {
        val jpeg = ByteArray(40) { (it * 3).toByte() }
        val items = arrayOf(
            atom("©nam", data(1, "My Baby".toByteArray())),
            atom("©ART", data(1, "Ada Lark".toByteArray())),
            atom("aART", data(1, "The Larks".toByteArray())),
            atom("©alb", data(1, "Songs".toByteArray())),
            atom("©day", data(1, "2015-03-01".toByteArray())),
            atom("trkn", data(0, byteArrayOf(0, 0, 0, 3, 0, 12, 0, 0))),
            atom("disk", data(0, byteArrayOf(0, 0, 0, 1, 0, 2))),
            atom("gnre", data(0, byteArrayOf(0, 18))),
            atom("covr", data(13, jpeg)),
        )
        for (first in listOf(true, false)) {
            val parsed = Mp4Metadata.read(ArrayByteSource(mp4(first, *items)))
            val meta = parsed.metadata
            assertEquals("My Baby", meta.title)
            assertEquals("Ada Lark", meta.artist)
            assertEquals("The Larks", meta.albumArtist)
            assertEquals("Songs", meta.album)
            assertEquals(2015, meta.year)
            assertEquals(3, meta.trackNumber)
            assertEquals(12, meta.trackTotal)
            assertEquals(1, meta.discNumber)
            assertEquals(2, meta.discTotal)
            assertEquals("Rock", meta.genre, message = "gnre is 1-based")
            assertEquals(90.5, meta.duration, 0.001)
            assertContentEquals(jpeg, assertNotNull(parsed.cover).data)
            assertEquals("image/jpeg", parsed.cover!!.mimeType)
        }
    }

    @Test
    fun mp4FreeformAtomsCarryReplayGainAndSoundCheck() {
        fun freeform(name: String, value: String) = atom(
            "----", atom("mean", ByteArray(4), "com.apple.iTunes".toByteArray()), atom("name", ByteArray(4), name.toByteArray()),
            data(1, value.toByteArray()),
        )
        val withGain = mp4(true, atom("©nam", data(1, "Song".toByteArray())), freeform("replaygain_track_gain", "-6.50 dB"),
                           freeform("replaygain_album_gain", "+9.00 dB"), freeform("replaygain_album_peak", "0.5"),
                           freeform("iTunNORM", " 0000018B 0000016F 0000290C 0000264E 00013105 00013105 00006000 00005800 00000000 00000000"))
        val meta = Mp4Metadata.read(ArrayByteSource(withGain)).metadata
        assertEquals("Song", meta.title)
        assertEquals(-6.5, meta.trackGain)
        assertTrue(kotlin.math.abs(meta.albumGain!! - 6.02) < 0.01, "limited by the peak")

        val checkOnly = mp4(true, freeform("iTunNORM", " 0000018B 0000016F 0000290C 0000264E 00013105 00013105 00006000 00005800 00000000 00000000"))
        val checked = Mp4Metadata.read(ArrayByteSource(checkOnly)).metadata
        assertTrue(kotlin.math.abs(checked.trackGain!! - 2.5) < 0.01, "+4.03 dB, limited by the 0.75 peak to 2.5 dB")
        assertNull(checked.albumGain)
    }

    @Test
    fun mp4TextGenreWinsOverIndex() {
        val meta = Mp4Metadata.read(ArrayByteSource(mp4(true, atom("©gen", data(1, "Shoegaze".toByteArray()))))).metadata
        assertEquals("Shoegaze", meta.genre)
        assertNull(meta.title)
    }

    @Test
    fun notAnMp4Throws() {
        assertFailsWith<Mp4Metadata.NotMp4> {
            Mp4Metadata.read(ArrayByteSource("ID3 something".toByteArray()))
            Unit
        }
    }

    /**
     * Against real files, when CRATE_MUSIC_DIRS (colon-separated folders)
     * is set: every MP3, M4A and M4B must yield a title and a duration,
     * and a summary is printed like crate-scan does.
     */
    @Test
    fun realFilesWhenAvailable() {
        val dirs = System.getenv("CRATE_MUSIC_DIRS")?.split(':')?.map(::File)?.filter { it.isDirectory } ?: return
        var files = 0
        var untitled = 0
        var noDuration = 0
        var noCover = 0
        val failures = mutableListOf<String>()
        for (dir in dirs) {
            dir.walkTopDown().filter { it.isFile && Paths.extension(it.name) in setOf("mp3", "m4a", "m4b") }.forEach { file ->
                files++
                val parsed = try {
                    FileByteSource(file).use { if (Paths.extension(file.name) == "mp3") Mp3Metadata.read(it) else Mp4Metadata.read(it) }
                } catch (e: Exception) {
                    failures.add("${file.name}: $e")
                    return@forEach
                }
                if (parsed.metadata.title == null) untitled++
                if (parsed.metadata.duration <= 0) noDuration++
                if (parsed.cover == null) noCover++
            }
        }
        println("real files: $files read, $untitled without title, $noDuration without duration, $noCover without cover, ${failures.size} failed")
        failures.forEach(::println)
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
        assertTrue(files > 0)
    }
}
