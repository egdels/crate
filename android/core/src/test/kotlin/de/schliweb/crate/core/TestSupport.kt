// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

fun makeTrack(
    name: String,
    artist: String? = null,
    album: String? = null,
    albumArtist: String? = null,
    track: Int? = null,
    disc: Int? = null,
    year: Int? = null,
    folder: String = "/music",
): Track = Track(
    uri = "file://$folder/$name.mp3",
    path = "$folder/$name.mp3",
    fileSize = 1,
    modified = 0,
    title = name,
    artist = artist,
    albumArtist = albumArtist,
    album = album,
    trackNumber = track,
    discNumber = disc,
    year = year,
    duration = 100.0,
)

/** A fresh, empty directory; call [delete] when done. */
class TemporaryDirectory {
    val dir: File = Files.createTempDirectory("CrateCoreTests").toFile()

    fun file(relativePath: String, contents: String = "x"): File {
        val file = File(dir, relativePath)
        file.parentFile.mkdirs()
        file.writeText(contents)
        return file
    }

    fun delete() {
        dir.deleteRecursively()
    }
}

/** Serves canned metadata and counts reads, so library tests can assert
 *  which files were (not) re-read. */
class FakeMetadataReader : MetadataReader {
    private val metadata = HashMap<String, TrackMetadata>()
    private val failing = HashSet<String>()
    val reads = mutableListOf<String>()

    class Unreadable : Exception("unreadable")

    fun set(file: File, meta: TrackMetadata) {
        metadata[file.path] = meta
    }

    fun fail(file: File) {
        failing.add(file.path)
    }

    override suspend fun read(file: ScannedFile): TrackMetadata {
        synchronized(reads) { reads.add(file.path) }
        if (file.path in failing) throw Unreadable()
        return metadata[file.path] ?: TrackMetadata()
    }
}

/** Builds the metadata part of a FLAC file by hand: marker, STREAMINFO,
 *  VORBIS_COMMENT, PICTURE. No audio frames; the parser never reads them. */
fun flacBytes(
    comments: List<String>,
    sampleRate: Int = 44_100,
    totalSamples: Long = 44_100L * 90,
    picture: ByteArray? = null,
): ByteArray {
    val out = ByteArrayOutputStream()
    out.write("fLaC".toByteArray())
    fun block(type: Int, body: ByteArray, last: Boolean) {
        out.write(type or (if (last) 0x80 else 0))
        out.write(body.size shr 16 and 0xff)
        out.write(body.size shr 8 and 0xff)
        out.write(body.size and 0xff)
        out.write(body)
    }
    val info = ByteArray(34)
    info[10] = (sampleRate shr 12 and 0xff).toByte()
    info[11] = (sampleRate shr 4 and 0xff).toByte()
    info[12] = (((sampleRate and 0x0f) shl 4) or ((2 - 1) shl 1)).toByte()
    info[13] = (totalSamples shr 32 and 0x0f).toByte()
    info[14] = (totalSamples shr 24 and 0xff).toByte()
    info[15] = (totalSamples shr 16 and 0xff).toByte()
    info[16] = (totalSamples shr 8 and 0xff).toByte()
    info[17] = (totalSamples and 0xff).toByte()
    block(0, info, last = false)
    val vorbis = ByteArrayOutputStream()
    fun le(n: Int) {
        vorbis.write(n and 0xff); vorbis.write(n shr 8 and 0xff); vorbis.write(n shr 16 and 0xff); vorbis.write(n shr 24 and 0xff)
    }
    val vendor = "test".toByteArray()
    le(vendor.size); vorbis.write(vendor)
    le(comments.size)
    for (comment in comments) {
        val bytes = comment.toByteArray(); le(bytes.size); vorbis.write(bytes)
    }
    block(4, vorbis.toByteArray(), last = picture == null)
    if (picture != null) {
        val pic = ByteArrayOutputStream()
        fun be(n: Int) {
            pic.write(n shr 24 and 0xff); pic.write(n shr 16 and 0xff); pic.write(n shr 8 and 0xff); pic.write(n and 0xff)
        }
        be(3); val mime = "image/png".toByteArray(); be(mime.size); pic.write(mime)
        be(0); be(8); be(8); be(24); be(0); be(picture.size); pic.write(picture)
        block(6, pic.toByteArray(), last = true)
    }
    return out.toByteArray()
}
