// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.library

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaCodecInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.RequiresApi
import android.util.Log
import de.schliweb.crate.core.LoudnessMeter
import de.schliweb.crate.core.Track
import de.schliweb.crate.core.TrackLoudness
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes a whole file with the platform's codecs and runs it through
 * the loudness meter; null when the file is silent or cannot be decoded.
 * In batches with callbacks where the decoder allows it, else buffer by
 * buffer on the calling worker thread; cancellable either way. 16-bit
 * PCM is what the decoders hand out by default, floats when a decoder
 * prefers them. One log line per file under the tag "Loudness".
 */
object MediaCodecLoudness {
    private const val TAG = "Loudness"

    suspend fun measure(context: Context, track: Track): TrackLoudness? {
        val started = System.currentTimeMillis()
        val result = try {
            decodeFile(context, track)
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "gave up: ${track.title} took longer than it plays")
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "failed: ${track.title}: $e")
            throw e
        }
        val seconds = (System.currentTimeMillis() - started) / 1000.0
        if (result == null) Log.w(TAG, "nothing measured: ${track.title} (${"%.1f".format(seconds)} s)")
        else Log.d(TAG, "%.1f LUFS, peak %.1f dBFS: %s (%.1f s audio in %.1f s)".format(result.loudness ?: Double.NaN, 20 * kotlin.math.log10(result.peak.coerceAtLeast(1e-6)), track.title, track.duration, seconds))
        return result
    }

    private suspend fun decodeFile(context: Context, track: Track): TrackLoudness? {
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && supportsBatches(codec, mime)) {
                    return decodeBatched(extractor, codec, format)
                }
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

    /** How long to wait for the decoder when nothing else is to do. */
    private const val WAIT_MICROS = 10_000L

    /** Batches: many access units per input buffer and many frames per
     *  output buffer, where the codec allows it (Android 15 and later,
     *  the platform's audio decoders do). Every buffer costs a round
     *  trip to the codec process, about 0.75 ms, and one MP3 frame is
     *  26 ms of audio: frame by frame, the decoder idles most of the
     *  time and a four-minute file takes eight seconds; in batches it
     *  takes under one. The batch mode exists only with callbacks. */
    private const val INPUT_BATCH_BYTES = 256 * 1024
    private const val OUTPUT_BATCH_BYTES = 1024 * 1024

    private fun supportsBatches(codec: MediaCodec, mime: String): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && runCatching {
            codec.codecInfo.getCapabilitiesForType(mime).isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_MultipleFrames)
        }.getOrDefault(false)

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private suspend fun decodeBatched(extractor: MediaExtractor, codec: MediaCodec, format: MediaFormat): TrackLoudness? {
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, INPUT_BATCH_BYTES)
        format.setInteger(MediaFormat.KEY_BUFFER_BATCH_MAX_OUTPUT_SIZE, OUTPUT_BATCH_BYTES)
        format.setInteger(MediaFormat.KEY_BUFFER_BATCH_THRESHOLD_OUTPUT_SIZE, OUTPUT_BATCH_BYTES / 2)
        val thread = HandlerThread("loudness-codec")
        thread.start()
        try {
            val done = CompletableDeferred<TrackLoudness?>()
            // All callbacks arrive on the handler thread, one at a time.
            codec.setCallback(object : MediaCodec.Callback() {
                val sink = PcmSink()
                var inputDone = false

                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    if (inputDone || done.isCompleted) return
                    try {
                        val buffer = codec.getInputBuffer(index) ?: return
                        inputDone = queueBatch(extractor, codec, index, buffer)
                    } catch (e: Exception) {
                        done.completeExceptionally(e)
                    }
                }

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    take(codec, index, listOf(info))
                }

                override fun onOutputBuffersAvailable(codec: MediaCodec, index: Int, infos: java.util.ArrayDeque<MediaCodec.BufferInfo>) {
                    take(codec, index, infos.toList())
                }

                private fun take(codec: MediaCodec, index: Int, infos: List<MediaCodec.BufferInfo>) {
                    if (done.isCompleted) return
                    try {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null) for (info in infos) sink.add(buffer, info.offset, info.size)
                        codec.releaseOutputBuffer(index, false)
                        if (infos.any { it.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 }) done.complete(sink.result)
                    } catch (e: Exception) {
                        done.completeExceptionally(e)
                    }
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    sink.formatChanged(format)
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    done.completeExceptionally(e)
                }
            }, Handler(thread.looper))
            codec.configure(format, null, null, 0)
            codec.start()
            return done.await()
        } finally {
            // No callback may touch the codec or the extractor once the
            // callers release them.
            thread.quitSafely()
            thread.join()
        }
    }

    /** Fills one input buffer with as many access units as fit and
     *  queues them in one go; true when the stream ended. */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun queueBatch(extractor: MediaExtractor, codec: MediaCodec, index: Int, buffer: ByteBuffer): Boolean {
        val infos = java.util.ArrayDeque<MediaCodec.BufferInfo>()
        var offset = 0
        var ended = false
        while (true) {
            val next = extractor.sampleSize
            if (next < 0) { ended = true; break }
            if (offset + next > buffer.capacity()) {
                if (infos.isEmpty()) ended = true // a unit larger than the buffer: give up
                break
            }
            val size = extractor.readSampleData(buffer, offset)
            if (size < 0) { ended = true; break }
            infos.add(MediaCodec.BufferInfo().apply { set(offset, size, extractor.sampleTime, 0) })
            offset += size
            extractor.advance()
        }
        if (ended) infos.add(MediaCodec.BufferInfo().apply { set(offset, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM) })
        codec.queueInputBuffers(index, infos)
        return ended
    }

    /** Turns the decoder's interleaved PCM into planar floats for the
     *  meter; shared by both decode paths. */
    private class PcmSink {
        private var meter: LoudnessMeter? = null
        private var channels = 0
        private var encoding = AudioFormat.ENCODING_PCM_16BIT
        private var planar: Array<FloatArray> = emptyArray()
        private var shorts = ShortArray(0)
        private var floats = FloatArray(0)

        val result: TrackLoudness? get() = meter?.result

        fun formatChanged(format: MediaFormat) {
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
            meter = LoudnessMeter(sampleRate.toDouble(), channels)
            planar = Array(channels) { FloatArray(0) }
        }

        fun add(buffer: ByteBuffer, offset: Int, size: Int) {
            val current = meter ?: return
            if (size <= 0 || channels == 0) return
            buffer.position(offset)
            buffer.limit(offset + size)
            buffer.order(ByteOrder.nativeOrder())
            val frames: Int
            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                val source = buffer.asFloatBuffer()
                val count = source.remaining()
                if (floats.size < count) floats = FloatArray(count)
                source.get(floats, 0, count)
                frames = count / channels
                if (planar[0].size < frames) planar = Array(channels) { FloatArray(frames) }
                for (c in 0 until channels) {
                    val plane = planar[c]
                    var j = c
                    for (i in 0 until frames) { plane[i] = floats[j]; j += channels }
                }
            } else {
                val source = buffer.asShortBuffer()
                val count = source.remaining()
                if (shorts.size < count) shorts = ShortArray(count)
                source.get(shorts, 0, count)
                frames = count / channels
                if (planar[0].size < frames) planar = Array(channels) { FloatArray(frames) }
                for (c in 0 until channels) {
                    val plane = planar[c]
                    var j = c
                    for (i in 0 until frames) { plane[i] = shorts[j] / 32768f; j += channels }
                }
            }
            current.add(planar, frames)
        }
    }

    private suspend fun decode(extractor: MediaExtractor, codec: MediaCodec): TrackLoudness? {
        val info = MediaCodec.BufferInfo()
        val sink = PcmSink()
        var inputDone = false
        while (true) {
            currentCoroutineContext().ensureActive()
            // Feed every free input buffer, then take every finished
            // output buffer, and wait only when neither moved.
            var moved = false
            while (!inputDone) {
                val input = codec.dequeueInputBuffer(0)
                if (input < 0) break
                moved = true
                val buffer = codec.getInputBuffer(input) ?: break
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    codec.queueInputBuffer(input, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
            while (true) {
                val output = codec.dequeueOutputBuffer(info, if (moved) 0 else WAIT_MICROS)
                if (output == MediaCodec.INFO_TRY_AGAIN_LATER) break
                moved = true
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    sink.formatChanged(codec.outputFormat)
                    continue
                }
                if (output < 0) continue
                codec.getOutputBuffer(output)?.let { sink.add(it, info.offset, info.size) }
                codec.releaseOutputBuffer(output, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return sink.result
            }
        }
    }
}
