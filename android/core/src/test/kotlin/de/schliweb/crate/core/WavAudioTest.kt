// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WavAudioTest {
    private class Bytes(private val bytes: ByteArray) : ByteSource {
        override val size: Long = bytes.size.toLong()
        override fun read(offset: Long, length: Int): ByteArray =
            if (offset >= size || length <= 0) ByteArray(0)
            else bytes.copyOfRange(offset.toInt(), minOf(size, offset + length).toInt())
        override fun close() = Unit
    }

    private fun le(size: Int, fill: ByteBuffer.() -> Unit): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(fill).array()

    private fun chunk(id: String, body: ByteArray, declared: Int = body.size): ByteArray =
        id.toByteArray() + le(4) { putInt(declared) } + body + (if (body.size % 2 == 1) byteArrayOf(0) else byteArrayOf())

    private fun fmt(kind: Int, channels: Int, rate: Int, bits: Int, subFormat: Int? = null): ByteArray =
        chunk("fmt ", le(if (subFormat == null) 16 else 40) {
            putShort(kind.toShort()); putShort(channels.toShort()); putInt(rate)
            putInt(rate * channels * bits / 8); putShort((channels * bits / 8).toShort()); putShort(bits.toShort())
            if (subFormat != null) { putShort(22); putShort(bits.toShort()); putInt(3); putShort(subFormat.toShort()) }
        })

    private fun wav(vararg chunks: ByteArray): Bytes {
        val body = ByteArrayOutputStream().apply { write("WAVE".toByteArray()); chunks.forEach { write(it) } }.toByteArray()
        return Bytes("RIFF".toByteArray() + le(4) { putInt(body.size) } + body)
    }

    private fun samples(source: ByteSource, wav: WavAudio): List<List<Float>> {
        val out = List(wav.channels) { ArrayList<Float>() }
        wav.read(source) { planar, frames -> for (c in out.indices) for (i in 0 until frames) out[c].add(planar[c][i]) }
        return out
    }

    @Test
    fun sixteenBitStereoBehindAnOddChunk() {
        val data = le(8) { putShort(16384); putShort(-32768); putShort(-16384); putShort(32767) }
        val source = wav(fmt(1, 2, 44_100, 16), chunk("LIST", byteArrayOf(1, 2, 3)), chunk("data", data))
        val wav = assertNotNull(WavAudio.open(source))
        assertEquals(44_100, wav.sampleRate)
        assertEquals(2, wav.channels)
        assertEquals(2L, wav.frames)
        assertEquals(listOf(listOf(0.5f, -0.5f), listOf(-1f, 32767 / 32768f)), samples(source, wav))
        // What a decoder would hand out lies in the file as it is.
        assertEquals(true, wav.isPcm16)
        assertEquals(4, wav.frameBytes)
        assertEquals(data.toList(), source.read(wav.dataOffset, (wav.frames * wav.frameBytes).toInt()).toList())
    }

    @Test
    fun otherWidthsAndFloats() {
        val eight = wav(fmt(1, 1, 8_000, 8), chunk("data", byteArrayOf(0, 128.toByte(), 192.toByte(), 0)))
        assertEquals(listOf(listOf(-1f, 0f, 0.5f, -1f)), samples(eight, WavAudio.open(eight)!!))
        assertEquals(false, WavAudio.open(eight)!!.isPcm16)

        val twentyFour = wav(fmt(1, 1, 48_000, 24), chunk("data", byteArrayOf(0, 0, 0x40, 0, 0, 0x80.toByte())))
        assertEquals(listOf(listOf(0.5f, -1f)), samples(twentyFour, WavAudio.open(twentyFour)!!))

        val thirtyTwo = wav(fmt(1, 1, 48_000, 32), chunk("data", le(8) { putInt(1 shl 30); putInt(Int.MIN_VALUE) }))
        assertEquals(listOf(listOf(0.5f, -1f)), samples(thirtyTwo, WavAudio.open(thirtyTwo)!!))

        // Extensible header, floats; a NaN in the file counts as silence.
        val floats = wav(fmt(0xfffe, 1, 96_000, 32, subFormat = 3), chunk("data", le(12) { putFloat(0.25f); putFloat(Float.NaN); putFloat(-0.75f) }))
        assertEquals(listOf(listOf(0.25f, 0f, -0.75f)), samples(floats, WavAudio.open(floats)!!))
        assertEquals(false, WavAudio.open(floats)!!.isPcm16)
        assertEquals(true, WavAudio.open(floats)!!.isFloat32)

        val doubles = wav(fmt(3, 1, 44_100, 64), chunk("data", le(16) { putDouble(0.125); putDouble(Double.POSITIVE_INFINITY) }))
        assertEquals(listOf(listOf(0.125f, 0f)), samples(doubles, WavAudio.open(doubles)!!))
        assertEquals(false, WavAudio.open(doubles)!!.isFloat32)
    }

    @Test
    fun aDataChunkWithoutItsSizeRunsToTheEndAndACutOneStopsThere() {
        val data = le(8) { putShort(1); putShort(2); putShort(3); putShort(4) }
        val streamed = wav(fmt(1, 1, 44_100, 16), chunk("data", data, declared = 0))
        assertEquals(4L, WavAudio.open(streamed)!!.frames)
        val cut = wav(fmt(1, 1, 44_100, 16), chunk("data", data, declared = 4000))
        assertEquals(4L, WavAudio.open(cut)!!.frames)
    }

    @Test
    fun manyChunksOfFrames() {
        val count = WavAudio.CHUNK_FRAMES * 2 + 5
        val source = wav(fmt(1, 2, 44_100, 16), chunk("data", le(count * 4) { repeat(count) { putShort((it % 100).toShort()); putShort(0) } }))
        val wav = WavAudio.open(source)!!
        var frames = 0L
        var calls = 0
        assertEquals(count.toLong(), wav.read(source) { planar, n ->
            assertEquals((frames % 100).toInt() / 32768f, planar[0][0])
            frames += n; calls++
        })
        assertEquals(count.toLong(), frames)
        assertEquals(3, calls)
    }

    @Test
    fun whatIsNotPlainPcmIsNotRead() {
        assertNull(WavAudio.open(Bytes("ID3 not a wav at all".toByteArray())))
        assertNull(WavAudio.open(wav(fmt(2, 2, 44_100, 4), chunk("data", ByteArray(64))))) // ADPCM
        assertNull(WavAudio.open(wav(fmt(1, 2, 44_100, 12), chunk("data", ByteArray(64)))))
        assertNull(WavAudio.open(wav(chunk("data", ByteArray(64))))) // no format before the samples
        assertNull(WavAudio.open(wav(fmt(1, 2, 44_100, 16)))) // no samples
    }
}
