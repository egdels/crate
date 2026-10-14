// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GainProcessorTest {
    private fun configured(): GainProcessor = GainProcessor().apply {
        configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
        flush()
    }

    private fun samples(vararg values: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(values.size * 2).order(ByteOrder.nativeOrder())
        for (v in values) buffer.putShort(v.toShort())
        buffer.flip()
        return buffer
    }

    private fun GainProcessor.process(input: ByteBuffer): List<Int> {
        queueInput(input)
        val out = output
        return List(out.remaining() / 2) { out.short.toInt() }
    }

    /** 0.6.0 threw "The source buffer is this buffer" here and stopped
     *  playback of every untagged file: the pipeline queues the shared
     *  empty buffer, and replaceOutputBuffer(0) returns that same object. */
    @Test
    fun emptyInputIsIgnoredAtUnityGain() {
        val processor = configured()
        processor.factor = 1f
        processor.queueInput(AudioProcessor.EMPTY_BUFFER)
        assertFalse(processor.output.hasRemaining())
        processor.queueInput(ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()))
        assertFalse(processor.output.hasRemaining())
    }

    @Test
    fun unityGainPassesSamplesThrough() {
        val processor = configured()
        processor.factor = 1f
        assertEquals(listOf(1000, -1000, 32767, -32768), processor.process(samples(1000, -1000, 32767, -32768)))
    }

    @Test
    fun gainScalesAndClips() {
        val processor = configured()
        processor.factor = 0.5f
        assertEquals(listOf(500, -500, 16383, -16384), processor.process(samples(1000, -1000, 32767, -32768)))
        processor.factor = 2f
        assertEquals(listOf(2000, 32767, -32768), processor.process(samples(1000, 20000, -20000)))
    }

    @Test
    fun otherFormatsPassUntouched() {
        val processor = GainProcessor()
        val float = processor.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT))
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, float)
        assertFalse(processor.isActive)
        assertTrue(configured().isActive)
    }
}
