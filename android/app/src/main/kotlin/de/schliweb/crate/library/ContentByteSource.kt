// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.library

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import de.schliweb.crate.core.ByteSource
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.nio.ByteBuffer

/** Positional reads on a document through its file descriptor: the
 *  provider hands over the descriptor once, then every read is a plain
 *  pread without another round trip. */
class ContentByteSource(context: Context, uri: Uri) : ByteSource {
    private val descriptor: ParcelFileDescriptor =
        context.contentResolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException(uri.toString())
    private val stream = FileInputStream(descriptor.fileDescriptor)
    private val channel = stream.channel

    override val size: Long = channel.size()

    override fun read(offset: Long, length: Int): ByteArray {
        if (offset >= size || length <= 0) return ByteArray(0)
        val count = minOf(length.toLong(), size - offset).toInt()
        val buffer = ByteBuffer.allocate(count)
        var position = offset
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, position)
            if (n <= 0) break
            position += n
        }
        return if (buffer.hasRemaining()) buffer.array().copyOf(buffer.position()) else buffer.array()
    }

    override fun close() {
        stream.close()
        descriptor.close()
    }
}
