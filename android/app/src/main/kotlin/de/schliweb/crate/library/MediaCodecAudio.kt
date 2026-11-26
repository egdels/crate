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
import de.schliweb.crate.core.WavAudio
import de.schliweb.crate.core.TrackLoudness
import de.schliweb.crate.core.Waveform
import de.schliweb.crate.core.WaveformBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Where decoded audio goes: planar floats, one array per channel, after
 * [start] has told the format. A second [start] begins anew: a file one
 * reader gave up on is read again from its beginning by another.
 */
interface PcmTarget<R> {
    fun start(sampleRate: Double, channels: Int)
    fun add(planar: Array<FloatArray>, frames: Int)

    /** The decoder's own buffer before it is taken apart: [size] bytes
     *  of interleaved PCM in native order at [offset], 16-bit or
     *  [floats]. True when the target took them; false, and the same
     *  audio comes through [add]. */
    fun addInterleaved(buffer: ByteBuffer, offset: Int, size: Int, floats: Boolean): Boolean = false

    val result: R?

    /** Nothing more will come, whether [result] was asked or not. */
    fun close() {}
}

/**
 * The loudness and the outline of one file. Buffers of interleaved PCM
 * go to [NativeAnalysis], which does the work per sample (filter,
 * energy, peak) and hands back whole steps and blocks; the meter and
 * the builder do the rest. Planar floats (the WAV reader's, or with the
 * native library missing) go through the meter and the builder alone.
 * The two give the same loudness and peak to the bit.
 */
private class MeasureTarget : PcmTarget<MediaCodecAudio.Measured> {
    private var meter: LoudnessMeter? = null
    private var builder: WaveformBuilder? = null
    private var native = 0L
    private var steps = DoubleArray(0)
    private val peaks = FloatArray(512)
    private val squares = DoubleArray(512)

    override fun start(sampleRate: Double, channels: Int) {
        close()
        val meter = LoudnessMeter(sampleRate, channels).also { meter = it }
        val builder = WaveformBuilder(sampleRate, channels).also { builder = it }
        if (NativeDecoder.available && channels > 0) {
            native = NativeAnalysis.open(channels, meter.filterCoefficients, meter.stepFrames, builder.blockFrames)
            steps = DoubleArray(64 * channels)
        }
    }

    override fun add(planar: Array<FloatArray>, frames: Int) {
        meter?.add(planar, frames)
        builder?.add(planar, frames)
    }

    override fun addInterleaved(buffer: ByteBuffer, offset: Int, size: Int, floats: Boolean): Boolean {
        if (native == 0L || !buffer.isDirect) return false
        check(NativeAnalysis.add(native, buffer, offset, size, floats)) { "out of memory" }
        drain()
        return true
    }

    private fun drain() {
        val meter = meter ?: return
        val builder = builder ?: return
        while (true) {
            val counts = NativeAnalysis.drain(native, steps, peaks, squares)
            if (counts == 0L) break
            meter.addSteps(steps, (counts ushr 32).toInt())
            for (i in 0 until counts.toInt()) builder.addBlock(peaks[i], squares[i], builder.blockFrames)
        }
    }

    // Read to the end: silence is a measurement too (no gain), or the
    // file would be decoded again on every launch.
    override val result: MediaCodecAudio.Measured
        get() {
            if (native != 0L) {
                drain()
                val frames = NativeAnalysis.finish(native, peaks, squares)
                if (frames > 0) builder?.addBlock(peaks[0], squares[0], frames)
                meter?.notePeak(NativeAnalysis.peak(native))
                close()
            }
            return MediaCodecAudio.Measured(meter?.finalResult, builder?.result())
        }

    override fun close() {
        if (native != 0L) NativeAnalysis.close(native)
        native = 0L
    }
}

/**
 * Decodes a whole file into a [PcmTarget]: the loudness meter
 * ([measure]) or the waveform ([waveform]); null when the file is
 * silent or cannot be decoded. MP3, FLAC and WAV, nearly all of a usual
 * library, are read in this process ([Mp3Decoder], [FlacDecoder],
 * [WavAudio]); the rest, and any file those give up on, goes through
 * the platform's codecs: in batches with callbacks where the decoder
 * allows it, else buffer by buffer on the calling worker thread.
 * Cancellable either way. 16-bit PCM is what the decoders hand out by
 * default, floats when a decoder prefers them. One log line per
 * measured file under the tag "Loudness".
 */
object MediaCodecAudio {
    private const val TAG = "Loudness"

    /** The outline of the track, for the practice panel. */
    suspend fun waveform(context: Context, track: Track): Waveform? =
        decodeFile(context, track, object : PcmTarget<Waveform> {
            private var builder: WaveformBuilder? = null
            override fun start(sampleRate: Double, channels: Int) {
                builder = WaveformBuilder(sampleRate, channels)
            }
            override fun add(planar: Array<FloatArray>, frames: Int) {
                builder?.add(planar, frames)
            }
            override val result: Waveform? get() = builder?.result()
        })

    /** What one pass over a file gives: its loudness and, since the
     *  samples go by anyway, its outline. */
    class Measured(val loudness: TrackLoudness?, val waveform: Waveform?)

    suspend fun measure(context: Context, track: Track): Measured? {
        val started = System.currentTimeMillis()
        val measured = try {
            decodeFile(context, track, MeasureTarget())
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
        val result = measured?.loudness
        if (result == null) Log.w(TAG, "nothing measured: ${track.title} (${"%.1f".format(seconds)} s)")
        else Log.d(TAG, "%.1f LUFS, peak %.1f dBFS: %s (%.1f s audio in %.1f s)".format(result.loudness ?: Double.NaN, 20 * kotlin.math.log10(result.peak.coerceAtLeast(1e-6)), track.title, track.duration, seconds))
        return measured
    }

    private suspend fun <R> decodeFile(context: Context, track: Track, target: PcmTarget<R>): R? {
        try {
            return decodeAnyWay(context, track, target)
        } finally {
            target.close()
        }
    }

    private suspend fun <R> decodeAnyWay(context: Context, track: Track, target: PcmTarget<R>): R? {
        val name = track.path.lowercase()
        val read = try {
            when {
                name.endsWith(".mp3") && NativeDecoder.available -> readNative(Mp3Decoder, context, track, target)
                name.endsWith(".flac") && NativeDecoder.available -> readNative(FlacDecoder, context, track, target)
                name.endsWith(".wav") -> readWav(context, track, target)
                else -> false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "own reader failed: ${track.title}: $e")
            false
        }
        return if (read) target.result else decodeWithPlatform(context, track, target)
    }

    /** Samples per [NativeDecoder.read]: 64 MP3 frames, about 1.7 s. */
    private const val NATIVE_BUFFER_SAMPLES = 1152 * 2 * 64

    /** The whole file through a decoder of the app's own; false when it
     *  is not a stream of that format after all, or when what came out
     *  is not as long as the tags say: bytes of another format can pass
     *  for a few MP3 frames, and the platform's decoder is the judge
     *  then. */
    private suspend fun <R> readNative(decoder: NativeDecoder, context: Context, track: Track, target: PcmTarget<R>): Boolean {
        val descriptor = context.contentResolver.openFileDescriptor(Uri.parse(track.uri), "r") ?: return false
        descriptor.use {
            val handle = decoder.open(descriptor.fd)
            if (handle == 0L) return false
            try {
                val buffer = ByteBuffer.allocateDirect(NATIVE_BUFFER_SAMPLES * 2).order(ByteOrder.nativeOrder())
                val sink = PcmSink(target)
                var rate = 0
                var channels = 0
                var frames = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = decoder.read(handle, buffer)
                    if (count <= 0) break
                    if (rate == 0) {
                        rate = decoder.rate(handle)
                        channels = decoder.channels(handle)
                        if (rate <= 0 || channels <= 0) return false
                        sink.start(rate, channels, AudioFormat.ENCODING_PCM_16BIT)
                    }
                    sink.add(buffer, 0, count * 2)
                    frames += count / channels
                }
                if (rate == 0) return false
                val seconds = frames.toDouble() / rate
                if (track.duration > 0 && kotlin.math.abs(seconds - track.duration) > maxOf(2.0, track.duration * 0.05)) {
                    Log.w(TAG, "own reader got %.1f s of %.1f s: %s".format(seconds, track.duration, track.title))
                    return false
                }
                return true
            } finally {
                decoder.close(handle)
            }
        }
    }

    /** The samples of a plain PCM WAV, straight from the file. 16-bit
     *  and 32-bit float files hold what a decoder hands out: their
     *  bytes go to the target as they are, in the pieces [WavAudio.read]
     *  would take, when the target wants them that way. */
    private suspend fun <R> readWav(context: Context, track: Track, target: PcmTarget<R>): Boolean {
        val job = currentCoroutineContext()[Job]
        ContentByteSource(context, Uri.parse(track.uri)).use { source ->
            val wav = WavAudio.open(source) ?: return false
            target.start(wav.sampleRate.toDouble(), wav.channels)
            if ((wav.isPcm16 || wav.isFloat32) && NativeDecoder.available && ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
                val buffer = ByteBuffer.allocateDirect(WavAudio.CHUNK_FRAMES * wav.frameBytes)
                var done = 0L
                while (done < wav.frames) {
                    job?.ensureActive()
                    buffer.clear()
                    buffer.limit(minOf(WavAudio.CHUNK_FRAMES.toLong(), wav.frames - done).toInt() * wav.frameBytes)
                    val wanted = buffer.limit()
                    val bytes = source.read(wav.dataOffset + done * wav.frameBytes, buffer)
                    val whole = bytes - bytes % wav.frameBytes
                    if (whole == 0) break
                    if (wav.isFloat32) NativeAnalysis.zeroNonFinite(buffer, whole)
                    // A target that does not take the first piece takes none.
                    if (!target.addInterleaved(buffer, 0, whole, wav.isFloat32)) break
                    done += whole / wav.frameBytes
                    if (bytes < wanted) break
                }
                if (done > 0) return true
            }
            return wav.read(source) { planar, frames ->
                job?.ensureActive()
                target.add(planar, frames)
            } > 0
        }
    }

    private suspend fun <R> decodeWithPlatform(context: Context, track: Track, target: PcmTarget<R>): R? {
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
                    return decodeBatched(extractor, codec, format, target)
                }
                codec.configure(format, null, null, 0)
                codec.start()
                return decode(extractor, codec, target)
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
    private suspend fun <R> decodeBatched(
        extractor: MediaExtractor, codec: MediaCodec, format: MediaFormat, target: PcmTarget<R>,
    ): R? {
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, INPUT_BATCH_BYTES)
        format.setInteger(MediaFormat.KEY_BUFFER_BATCH_MAX_OUTPUT_SIZE, OUTPUT_BATCH_BYTES)
        format.setInteger(MediaFormat.KEY_BUFFER_BATCH_THRESHOLD_OUTPUT_SIZE, OUTPUT_BATCH_BYTES / 2)
        val thread = HandlerThread("loudness-codec")
        thread.start()
        try {
            val done = CompletableDeferred<R?>()
            // All callbacks arrive on the handler thread, one at a time.
            codec.setCallback(object : MediaCodec.Callback() {
                val sink = PcmSink(target)
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

    /** Hands the decoder's interleaved PCM to the target, as it is when
     *  the target takes that, else as planar floats; shared by all
     *  decode paths. */
    private class PcmSink<R>(private val target: PcmTarget<R>) {
        private var started = false
        private var channels = 0
        private var encoding = AudioFormat.ENCODING_PCM_16BIT
        private var planar: Array<FloatArray> = emptyArray()
        private var shorts = ShortArray(0)
        private var floats = FloatArray(0)

        val result: R? get() = target.result

        fun formatChanged(format: MediaFormat) = start(
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
            if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT,
        )

        fun start(sampleRate: Int, channels: Int, encoding: Int) {
            this.channels = channels
            this.encoding = encoding
            target.start(sampleRate.toDouble(), channels)
            started = true
            planar = Array(channels) { FloatArray(0) }
        }

        fun add(buffer: ByteBuffer, offset: Int, size: Int) {
            if (!started) return
            if (size <= 0 || channels == 0) return
            buffer.position(offset)
            buffer.limit(offset + size)
            buffer.order(ByteOrder.nativeOrder())
            if (target.addInterleaved(buffer, offset, size, encoding == AudioFormat.ENCODING_PCM_FLOAT)) return
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
            target.add(planar, frames)
        }
    }

    private suspend fun <R> decode(extractor: MediaExtractor, codec: MediaCodec, target: PcmTarget<R>): R? {
        val info = MediaCodec.BufferInfo()
        val sink = PcmSink(target)
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
