// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * Random access to a file's bytes for the tag parsers, which need the
 * head (ID3v2, FLAC blocks), sometimes the tail (ID3v1, an MP4 `moov`
 * atom written after the audio) and nothing in between. The app backs
 * this with a file descriptor from the document provider, the tests
 * with a plain file.
 */
interface ByteSource : Closeable {
    val size: Long

    /** Up to [length] bytes at [offset]; shorter at the end of the file,
     *  empty beyond it. */
    fun read(offset: Long, length: Int): ByteArray
}

class FileByteSource(file: File) : ByteSource {
    private val raf = RandomAccessFile(file, "r")

    override val size: Long = raf.length()

    override fun read(offset: Long, length: Int): ByteArray {
        if (offset >= size || length <= 0) return ByteArray(0)
        val count = minOf(length.toLong(), size - offset).toInt()
        val out = ByteArray(count)
        raf.seek(offset)
        raf.readFully(out)
        return out
    }

    override fun close() = raf.close()
}

/** An embedded picture, as the tags carry it. */
class Picture(
    /** 3 is the front cover in FLAC and ID3 terms; MP4 has no type. */
    val type: Int,
    val mimeType: String,
    val data: ByteArray,
)

/** What a tag parser returns: the fields and the pictures. */
class ParsedTags(val metadata: TrackMetadata, val pictures: List<Picture>) {
    /** The front cover if there is one, otherwise the first picture. */
    val cover: Picture? get() = pictures.firstOrNull { it.type == 3 } ?: pictures.firstOrNull()
}

/** Big-endian helpers shared by the parsers. */
internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xff
internal fun ByteArray.u16(i: Int): Int = (u8(i) shl 8) or u8(i + 1)
internal fun ByteArray.u24(i: Int): Int = (u8(i) shl 16) or (u8(i + 1) shl 8) or u8(i + 2)
internal fun ByteArray.u32(i: Int): Long = (u8(i).toLong() shl 24) or (u8(i + 1).toLong() shl 16) or (u8(i + 2).toLong() shl 8) or u8(i + 3).toLong()
internal fun ByteArray.u64(i: Int): Long = (u32(i) shl 32) or u32(i + 4)
