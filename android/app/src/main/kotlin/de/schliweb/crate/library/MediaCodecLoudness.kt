// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.library

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import de.schliweb.crate.core.LoudnessMeter
import de.schliweb.crate.core.Track
import de.schliweb.crate.core.TrackLoudness
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.ByteOrder

/**
 * Decodes a whole file with the platform's codecs and runs it through
 * the loudness meter; null when the file is silent or cannot be decoded.
 * Blocking work for a worker thread; checks for cancellation between
 * buffers. 16-bit PCM is what the decoders hand out by default, floats
 * when a decoder prefers them.
 */
object MediaCodecLoudness {
    suspend fun measure(context: Context, track: Track): TrackLoudness? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, Uri.parse(track.uri), null)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                return decode(extractor, codec)
            } finally {
                codec.stop()
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private suspend fun decode(extractor: MediaExtractor, codec: MediaCodec): TrackLoudness? {
        val info = MediaCodec.BufferInfo()
        var meter: LoudnessMeter? = null
        var sampleRate = 0
        var channels = 0
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        var inputDone = false
        var planar: Array<FloatArray> = emptyArray()
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!inputDone) {
                val input = codec.dequeueInputBuffer(10_000)
                if (input >= 0) {
                    val buffer = codec.getInputBuffer(input) ?: continue
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(input, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val output = codec.dequeueOutputBuffer(info, 10_000)
            when {
                output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    meter = LoudnessMeter(sampleRate.toDouble(), channels)
                    planar = Array(channels) { FloatArray(0) }
                }
                output >= 0 -> {
                    val buffer = codec.getOutputBuffer(output)
                    val current = meter
                    if (buffer != null && current != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.order(ByteOrder.nativeOrder())
                        val frames: Int
                        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            val floats = buffer.asFloatBuffer()
                            frames = floats.remaining() / channels
                            if (planar[0].size < frames) planar = Array(channels) { FloatArray(frames) }
                            for (i in 0 until frames) for (c in 0 until channels) planar[c][i] = floats.get()
                        } else {
                            val shorts = buffer.asShortBuffer()
                            frames = shorts.remaining() / channels
                            if (planar[0].size < frames) planar = Array(channels) { FloatArray(frames) }
                            for (i in 0 until frames) for (c in 0 until channels) planar[c][i] = shorts.get() / 32768f
                        }
                        current.add(planar, frames)
                    }
                    codec.releaseOutputBuffer(output, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
        return meter?.result
    }
}
