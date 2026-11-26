// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The outline of a file in equal slices of time, for finding a passage
 * by eye. Not stored; the app decodes the file when it starts playing.
 *
 * What is drawn is [levels], the RMS of each slice: a mastered record
 * reaches full scale nearly everywhere, so its peaks make one solid
 * block, while the RMS still tells a quiet verse from a loud chorus.
 * The same as the Mac's `Waveform`.
 */
class Waveform(
    /** One value per slice, 0..1, the loudest sample of any channel. */
    val peaks: FloatArray,
    /** One value per slice, 0..1, the RMS over all channels. */
    val levels: FloatArray,
    val duration: Double,
) {
    /** The loudest slice, for scaling the drawing to the file; never 0. */
    val maximum: Float get() = max(peaks.maxOrNull() ?: 0f, 0.001f)
    /** The slice with the most energy; never 0. */
    val maximumLevel: Float get() = max(levels.maxOrNull() ?: 0f, 0.001f)
}

/**
 * Collects decoded audio into a [Waveform]. The length of a file is not
 * known for certain until it is decoded, so the peaks are first kept per
 * block of ten milliseconds and reduced to slices at the end.
 */
class WaveformBuilder(private val sampleRate: Double, private val channels: Int) {
    /** The frames of one block, ten milliseconds. */
    val blockFrames = max(1, (sampleRate / 100).toInt())
    private var blocks = FloatArray(4096)
    private var squares = DoubleArray(4096)
    private var sizes = IntArray(4096)
    private var blockCount = 0
    private var framesInBlock = 0
    private var current = 0f
    private var currentSquares = 0.0
    private var totalFrames = 0L

    /** Planar samples, one array per channel, the first [frames] valid.
     *  Block by block with the running values in locals: as fields they
     *  went through memory on every sample (see `KFilter.energy`), and
     *  the loudness analysis sends every sample of the library here. */
    fun add(planar: Array<FloatArray>, frames: Int) {
        val count = min(channels, planar.size)
        var offset = 0
        while (offset < frames) {
            val end = offset + min(frames - offset, blockFrames - framesInBlock)
            var peak = current
            var sum = currentSquares
            for (c in 0 until count) {
                val data = planar[c]
                for (i in offset until end) {
                    val sample = data[i]
                    val level = abs(sample)
                    if (level > peak) peak = level
                    sum += sample.toDouble() * sample
                }
            }
            current = peak
            currentSquares = sum
            framesInBlock += end - offset
            offset = end
            if (framesInBlock == blockFrames) closeBlock()
        }
        totalFrames += frames
    }

    /** One block measured elsewhere: the largest sample magnitude and
     *  the sum of the squared samples of all channels over [frames]
     *  frames, [blockFrames] of them except in a file's last block.
     *  Instead of [add], never mixed with it. */
    fun addBlock(peak: Float, squares: Double, frames: Int) {
        current = peak
        currentSquares = squares
        framesInBlock = frames
        closeBlock()
        totalFrames += frames
    }

    private fun closeBlock() {
        if (blockCount == blocks.size) {
            blocks = blocks.copyOf(blocks.size * 2)
            squares = squares.copyOf(squares.size * 2)
            sizes = sizes.copyOf(sizes.size * 2)
        }
        blocks[blockCount] = min(current, 1f)
        squares[blockCount] = currentSquares
        sizes[blockCount] = framesInBlock * max(1, channels)
        blockCount++
        current = 0f
        currentSquares = 0.0
        framesInBlock = 0
    }

    /** Reduces what was added to at most [slices] peaks. */
    fun result(slices: Int = 1000): Waveform {
        if (framesInBlock > 0) closeBlock()
        val count = max(1, min(slices, blockCount))
        val peaks = FloatArray(count)
        val sums = DoubleArray(count)
        val samples = LongArray(count)
        for (b in 0 until blockCount) {
            val slice = (b.toLong() * count / max(1, blockCount)).toInt()
            if (blocks[b] > peaks[slice]) peaks[slice] = blocks[b]
            sums[slice] += squares[b]
            samples[slice] += sizes[b].toLong()
        }
        val levels = FloatArray(count) { i ->
            if (samples[i] > 0) min(1.0, sqrt(sums[i] / samples[i])).toFloat() else 0f
        }
        return Waveform(peaks, levels, totalFrames / sampleRate)
    }
}

/**
 * Waveforms kept on disk, one small file per track, so that a track's
 * outline is there at once the second time and, for every file the
 * loudness analysis measured, the first time too: the analysis decodes
 * the whole file anyway and stores the outline on the way. A file is
 * valid while the track's size and modification time are the ones it
 * was made from; the directory may be cleared at any time (it lives in
 * the app's cache).
 *
 * Format: "PZW1", size, modified, duration, the slice count, then the
 * peaks and the levels, one byte each (0..255 for 0..1), about 2 KB.
 */
class FileWaveformCache(private val directory: File) {
    fun get(track: Track): Waveform? {
        val file = fileFor(track)
        if (!file.exists()) return null
        return runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                val magic = ByteArray(4).also { input.readFully(it) }
                if (!magic.contentEquals(MAGIC)) return null
                if (input.readLong() != track.fileSize || input.readLong() != track.modified) return null
                val duration = input.readDouble()
                val count = input.readInt()
                if (count !in 1..100_000) return null
                val peaks = ByteArray(count).also { input.readFully(it) }
                val levels = ByteArray(count).also { input.readFully(it) }
                Waveform(FloatArray(count) { unquantize(peaks[it]) }, FloatArray(count) { unquantize(levels[it]) }, duration)
            }
        }.getOrNull()
    }

    fun put(track: Track, waveform: Waveform) {
        directory.mkdirs()
        val file = fileFor(track)
        val temp = File(file.path + ".tmp")
        DataOutputStream(temp.outputStream().buffered()).use { output ->
            output.write(MAGIC)
            output.writeLong(track.fileSize)
            output.writeLong(track.modified)
            output.writeDouble(waveform.duration)
            output.writeInt(waveform.peaks.size)
            output.write(ByteArray(waveform.peaks.size) { quantize(waveform.peaks[it]) })
            output.write(ByteArray(waveform.levels.size) { quantize(waveform.levels[it]) })
        }
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    private fun fileFor(track: Track): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(track.id.toByteArray())
        return File(directory, digest.joinToString("") { "%02x".format(it) }.take(32) + ".wave")
    }

    private companion object {
        val MAGIC = "PZW1".toByteArray()

        fun quantize(value: Float): Byte = (value.coerceIn(0f, 1f) * 255 + 0.5f).toInt().toByte()

        fun unquantize(value: Byte): Float = (value.toInt() and 0xff) / 255f
    }
}
