// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

/**
 * The samples of a WAV file, read straight from the file: it holds plain
 * PCM, and sending it through a decoder in another process made it the
 * slowest format to measure after the MP3s got their own decoder (141
 * times real time on a Pixel 7a). Integer PCM of 8, 16, 24 or 32 bits
 * and floats of 32 or 64, plain or in the extensible header; anything
 * else (ADPCM, RF64) is not read here.
 */
class WavAudio private constructor(
    val sampleRate: Int,
    val channels: Int,
    private val bits: Int,
    private val float: Boolean,
    /** Where the samples begin in the file. */
    val dataOffset: Long,
    private val dataSize: Long,
) {
    /** The bytes of one frame, all channels. */
    val frameBytes = channels * bits / 8

    val frames: Long get() = dataSize / frameBytes

    /** True when the file's samples are what a decoder hands out as
     *  well, interleaved and little-endian, 16-bit PCM or 32-bit floats
     *  ([isFloat32]): [frames] of them from [dataOffset] can be passed
     *  on as they are, without [read]. Floats may be no numbers, which
     *  [read] turns into silence and the taker has to as well. */
    val isPcm16: Boolean get() = !float && bits == 16
    val isFloat32: Boolean get() = float && bits == 32

    /** Hands every frame to [add] as planar floats, at most [CHUNK_FRAMES]
     *  at a time, in arrays that are reused; [add] may throw to stop.
     *  Returns the frames read, fewer than [frames] for a cut file. */
    fun read(source: ByteSource, add: (planar: Array<FloatArray>, frames: Int) -> Unit): Long {
        val planar = Array(channels) { FloatArray(CHUNK_FRAMES) }
        var done = 0L
        while (done < frames) {
            val want = minOf(CHUNK_FRAMES.toLong(), frames - done).toInt()
            val bytes = source.read(dataOffset + done * frameBytes, want * frameBytes)
            val count = bytes.size / frameBytes
            if (count == 0) break
            for (c in 0 until channels) convert(bytes, c * bits / 8, planar[c], count)
            add(planar, count)
            done += count
            if (count < want) break
        }
        return done
    }

    /** One channel's samples out of the interleaved little-endian bytes. */
    private fun convert(bytes: ByteArray, first: Int, plane: FloatArray, count: Int) {
        var p = first
        val step = frameBytes
        when {
            float && bits == 32 -> for (i in 0 until count) {
                plane[i] = finite(Float.fromBits(int(bytes, p)))
                p += step
            }
            float -> for (i in 0 until count) {
                val low = int(bytes, p).toLong() and 0xffffffffL
                plane[i] = finite(Double.fromBits(low or (int(bytes, p + 4).toLong() shl 32)).toFloat())
                p += step
            }
            bits == 8 -> for (i in 0 until count) {
                plane[i] = ((bytes[p].toInt() and 0xff) - 128) / 128f
                p += step
            }
            bits == 16 -> for (i in 0 until count) {
                plane[i] = ((bytes[p].toInt() and 0xff) or (bytes[p + 1].toInt() shl 8)) / 32768f
                p += step
            }
            bits == 24 -> for (i in 0 until count) {
                plane[i] = ((bytes[p].toInt() and 0xff) or ((bytes[p + 1].toInt() and 0xff) shl 8) or (bytes[p + 2].toInt() shl 16)) / 8388608f
                p += step
            }
            else -> for (i in 0 until count) {
                plane[i] = int(bytes, p) / 2147483648f
                p += step
            }
        }
    }

    companion object {
        const val CHUNK_FRAMES = 16_384

        private const val PCM = 1
        private const val IEEE_FLOAT = 3
        private const val EXTENSIBLE = 0xfffe

        /** The format and the place of the samples, or null when the file
         *  is not a WAV this reads. */
        fun open(source: ByteSource): WavAudio? {
            val head = source.read(0, 12)
            if (head.size < 12 || !tag(head, 0, "RIFF") || !tag(head, 8, "WAVE")) return null
            var format: ByteArray? = null
            var offset = 12L
            while (offset + 8 <= source.size) {
                val chunk = source.read(offset, 8)
                if (chunk.size < 8) return null
                val size = int(chunk, 4).toLong() and 0xffffffffL
                when {
                    tag(chunk, 0, "fmt ") -> format = source.read(offset + 8, minOf(size, 40L).toInt())
                    tag(chunk, 0, "data") -> {
                        val fmt = format ?: return null
                        // A streamed file says 0 or all ones: to the end then.
                        val left = source.size - (offset + 8)
                        return make(fmt, offset + 8, if (size == 0L || size > left) left else size)
                    }
                }
                offset += 8 + size + (size and 1)
            }
            return null
        }

        private fun make(fmt: ByteArray, dataOffset: Long, dataSize: Long): WavAudio? {
            if (fmt.size < 16) return null
            var kind = short(fmt, 0)
            val channels = short(fmt, 2)
            val sampleRate = int(fmt, 4)
            val bits = short(fmt, 14)
            if (kind == EXTENSIBLE) {
                if (fmt.size < 26) return null
                kind = short(fmt, 24) // the sub-format's first two bytes
            }
            val known = when (kind) {
                PCM -> bits == 8 || bits == 16 || bits == 24 || bits == 32
                IEEE_FLOAT -> bits == 32 || bits == 64
                else -> false
            }
            if (!known || channels !in 1..8 || sampleRate !in 1_000..768_000) return null
            if (dataSize < channels * bits / 8) return null
            return WavAudio(sampleRate, channels, bits, kind == IEEE_FLOAT, dataOffset, dataSize)
        }

        private fun tag(bytes: ByteArray, at: Int, name: String): Boolean =
            (0 until 4).all { bytes[at + it] == name[it].code.toByte() }

        private fun short(bytes: ByteArray, at: Int): Int = (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)

        private fun int(bytes: ByteArray, at: Int): Int =
            (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8) or
                ((bytes[at + 2].toInt() and 0xff) shl 16) or (bytes[at + 3].toInt() shl 24)

        /** A float file may hold anything; one NaN would spoil the whole
         *  measurement (and, as JSON, the file it is saved in). */
        private fun finite(value: Float): Float = if (value.isFinite()) value else 0f
    }
}
