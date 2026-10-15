// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.tan

/**
 * Loudness the way ReplayGain 2.0 wants it measured: ITU-R BS.1770-4,
 * which is EBU R 128. Samples go through the K-weighting filter (a high
 * shelf, then a high-pass), their power is taken over 400 ms blocks
 * every 100 ms, blocks quieter than -70 LUFS are dropped, then blocks
 * more than 10 LU below the mean of the rest, and the integrated
 * loudness is the mean power of what survives. The blocks are kept as a
 * histogram, so an album's loudness can be gated over all its tracks'
 * blocks without keeping the blocks. A port of the Swift meter.
 */
class LoudnessMeter(val sampleRate: Double, val channelCount: Int) {
    private val filters = Array(channelCount) { KFilter(sampleRate) }
    private val weights = DoubleArray(channelCount) { index ->
        when {
            channelCount < 6 -> 1.0
            index == 3 -> 0.0
            index == 4 || index == 5 -> 1.41
            else -> 1.0
        }
    }
    private val stepFrames = Math.round(sampleRate * 0.1).toInt()
    private var stepSums = DoubleArray(channelCount)
    private var stepFilled = 0
    private val ring = ArrayDeque<DoubleArray>()
    private val histogram = LoudnessHistogram()
    private var peak = 0f

    /** Planar samples, one array per channel; [frames] of each. */
    fun add(channels: Array<FloatArray>, frames: Int = channels.firstOrNull()?.size ?: 0) {
        var offset = 0
        while (offset < frames) {
            val count = minOf(frames - offset, stepFrames - stepFilled)
            for (c in 0 until minOf(channelCount, channels.size)) {
                val data = channels[c]
                val filter = filters[c]
                var sum = 0.0
                var peak = this.peak
                for (i in offset until offset + count) {
                    val x = data[i]
                    val magnitude = abs(x)
                    if (magnitude > peak) peak = magnitude
                    val y = filter.process(x.toDouble())
                    sum += y * y
                }
                this.peak = peak
                stepSums[c] += sum
            }
            stepFilled += count
            offset += count
            if (stepFilled == stepFrames) stepDone()
        }
    }

    private fun stepDone() {
        ring.addLast(stepSums)
        if (ring.size > 4) ring.removeFirst()
        stepSums = DoubleArray(channelCount)
        stepFilled = 0
        if (ring.size < 4) return
        var power = 0.0
        for (c in 0 until channelCount) {
            var sum = 0.0
            for (step in ring) sum += step[c]
            power += weights[c] * sum / (4.0 * stepFrames)
        }
        histogram.add(power)
    }

    /** What was measured so far; null before the first full block. */
    val result: TrackLoudness?
        get() = if (histogram.blockCount > 0) TrackLoudness(histogram.integratedLoudness, peak.toDouble(), histogram.copy()) else null
}

/** The two BS.1770 biquads: a high shelf of about +4 dB above 1.5 kHz
 *  for the head, then a high-pass at 38 Hz. */
internal class KFilter(fs: Double) {
    private val b0: Double; private val b1: Double; private val b2: Double; private val a1: Double; private val a2: Double
    private val hb0: Double; private val hb1: Double; private val hb2: Double; private val ha1: Double; private val ha2: Double
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
    private var hx1 = 0.0; private var hx2 = 0.0; private var hy1 = 0.0; private var hy2 = 0.0

    init {
        val f0 = 1681.974450955533; val gain = 3.999843853973347; val q = 0.7071752369554196
        val k = tan(Math.PI * f0 / fs)
        val vh = 10.0.pow(gain / 20)
        val vb = vh.pow(0.4996667741545416)
        val a0 = 1 + k / q + k * k
        b0 = (vh + vb * k / q + k * k) / a0
        b1 = 2 * (k * k - vh) / a0
        b2 = (vh - vb * k / q + k * k) / a0
        a1 = 2 * (k * k - 1) / a0
        a2 = (1 - k / q + k * k) / a0
        val hf0 = 38.13547087602444; val hq = 0.5003270373238773
        val hk = tan(Math.PI * hf0 / fs)
        val ha0 = 1 + hk / hq + hk * hk
        hb0 = 1 / ha0
        hb1 = -2 / ha0
        hb2 = 1 / ha0
        ha1 = 2 * (hk * hk - 1) / ha0
        ha2 = (1 - hk / hq + hk * hk) / ha0
    }

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        val z = hb0 * y + hb1 * hx1 + hb2 * hx2 - ha1 * hy1 - ha2 * hy2
        hx2 = hx1; hx1 = y; hy2 = hy1; hy1 = z
        return z
    }
}

/** Gated blocks as counts and summed power per 1 LU from -70 LUFS up;
 *  see the Swift twin for why 1 LU. Encoded as [index, count, power]
 *  triples. */
@Serializable(with = LoudnessHistogram.Companion::class)
class LoudnessHistogram(bins: Map<Int, Bin> = emptyMap()) {
    data class Bin(var count: Int, var power: Double)

    private val bins: MutableMap<Int, Bin> = bins.mapValues { Bin(it.value.count, it.value.power) }.toMutableMap()

    val blockCount: Int get() = bins.values.sumOf { it.count }
    val binCount: Int get() = bins.size

    fun copy(): LoudnessHistogram = LoudnessHistogram(bins)

    /** Drops the block below the absolute gate; keeps the rest. */
    fun add(power: Double) {
        if (power <= 0) return
        val loudness = loudnessOf(power)
        if (loudness < LOWEST) return
        val index = minOf(BIN_COUNT - 1, ((loudness - LOWEST) / BIN_WIDTH).toInt())
        val bin = bins.getOrPut(index) { Bin(0, 0.0) }
        bin.count += 1
        bin.power += power
    }

    fun merge(other: LoudnessHistogram) {
        for ((index, bin) in other.bins) {
            val mine = bins.getOrPut(index) { Bin(0, 0.0) }
            mine.count += bin.count
            mine.power += bin.power
        }
    }

    /** null when no block passed the absolute gate. */
    val integratedLoudness: Double?
        get() {
            var count = 0
            var power = 0.0
            for (bin in bins.values) { count += bin.count; power += bin.power }
            if (count == 0) return null
            val threshold = loudnessOf(power / count) - 10
            var gatedCount = 0
            var gatedPower = 0.0
            for ((index, bin) in bins) {
                if (LOWEST + (index + 0.5) * BIN_WIDTH >= threshold) { gatedCount += bin.count; gatedPower += bin.power }
            }
            if (gatedCount == 0) return null
            return loudnessOf(gatedPower / gatedCount)
        }

    companion object : KSerializer<LoudnessHistogram> {
        const val LOWEST = -70.0
        const val BIN_WIDTH = 1.0
        const val BIN_COUNT = 80

        fun loudnessOf(power: Double): Double = -0.691 + 10 * log10(power)

        private val delegate = ListSerializer(JsonElement.serializer())
        override val descriptor: SerialDescriptor = delegate.descriptor

        override fun serialize(encoder: Encoder, value: LoudnessHistogram) {
            val triples = value.bins.entries.sortedBy { it.key }.map { (index, bin) ->
                // Four significant digits: hundredths of a dB.
                JsonArray(listOf(JsonPrimitive(index), JsonPrimitive(bin.count), JsonPrimitive("%.4g".format(java.util.Locale.ROOT, bin.power).toDouble())))
            }
            encoder.encodeSerializableValue(delegate, triples)
        }

        override fun deserialize(decoder: Decoder): LoudnessHistogram {
            val bins = HashMap<Int, Bin>()
            for (element in decoder.decodeSerializableValue(delegate)) {
                val triple = element as JsonArray
                bins[(triple[0] as JsonPrimitive).int] = Bin((triple[1] as JsonPrimitive).int, (triple[2] as JsonPrimitive).double)
            }
            return LoudnessHistogram(bins)
        }
    }
}

/** One track's measurement: integrated loudness in LUFS (null for a
 *  track that never rises above -70 LUFS), the sample peak as a linear
 *  factor, and the blocks for album gating. */
@Serializable
data class TrackLoudness(val loudness: Double?, val peak: Double, val histogram: LoudnessHistogram) {
    /** ReplayGain 2.0: the gain that brings the track to -18 LUFS, limited
     *  by the peak so nothing clips. null for silence. */
    val gain: Double? get() = loudness?.let { ReplayGain.limited(ReplayGain.REFERENCE - it, peak) }

    companion object {
        /** The album gain over several tracks' blocks and the loudest peak. */
        fun albumGain(tracks: List<TrackLoudness>): Double? {
            val histogram = LoudnessHistogram()
            var peak = 0.0
            for (track in tracks) { histogram.merge(track.histogram); peak = max(peak, track.peak) }
            return histogram.integratedLoudness?.let { ReplayGain.limited(ReplayGain.REFERENCE - it, peak) }
        }
    }
}

/** The measurements of the library's files, in `loudness.json` next to
 *  the index: one entry per track id with the file's modification time,
 *  so a changed file is measured again. Only files without ReplayGain
 *  tags are measured. Immutable, like [Favorites]. */
data class Loudness(val entries: Map<String, Entry> = emptyMap()) {
    @Serializable
    data class Entry(val modified: Long, val measurement: TrackLoudness)

    val size: Int get() = entries.size

    fun measurement(track: Track): TrackLoudness? =
        entries[track.id]?.takeIf { it.modified == track.modified }?.measurement

    fun isFresh(track: Track): Boolean = measurement(track) != null

    fun set(measurement: TrackLoudness, track: Track): Loudness =
        Loudness(entries + (track.id to Entry(track.modified, measurement)))

    fun trackGain(track: Track): Double? = measurement(track)?.gain

    /** The gain for the tracks played as one record; null unless every
     *  one of them is measured. */
    fun albumGain(tracks: List<Track>): Double? {
        val measurements = tracks.map { measurement(it) ?: return null }
        return TrackLoudness.albumGain(measurements)
    }

    fun removeAllUnder(folderPath: String): Loudness = Loudness(entries.filterKeys { !Paths.isUnder(it, folderPath) })

    companion object {
        /** Whether the track is one the analysis measures: it has no
         *  ReplayGain tags (a file with those has an album gain). */
        fun needsMeasuring(track: Track): Boolean = track.albumGain == null
    }
}

interface LoudnessStore {
    fun load(): Loudness?
    fun save(loudness: Loudness)
}

class JsonLoudnessStore(val file: File) : LoudnessStore {
    @Serializable
    private data class FileContents(val version: Int = CURRENT_VERSION, val entries: Map<String, Loudness.Entry> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun load(): Loudness? {
        if (!file.exists()) return null
        return Loudness(json.decodeFromString(FileContents.serializer(), file.readText()).entries)
    }

    override fun save(loudness: Loudness) {
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(json.encodeToString(FileContents.serializer(), FileContents(entries = loudness.entries)))
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) throw java.io.IOException("could not replace ${file.path}")
        }
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

/**
 * Measures the library's files that carry no ReplayGain tags, a few at a
 * time, and keeps the results in the store. Results are taken as they
 * finish, so one long file holds up one slot and not the count; a file
 * that takes longer than its own playing time (and at least
 * [minTimeoutMillis]) is given up as unmeasured and tried again on the
 * next run.
 */
class LoudnessAnalyzer(
    private val store: LoudnessStore,
    private val measure: suspend (Track) -> TrackLoudness?,
    private val concurrency: Int = 2,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val minTimeoutMillis: Long = 120_000,
) {
    data class Progress(val done: Int, val total: Int)

    @Volatile
    var loudness = Loudness()
        private set
    private var loaded = false

    /** Reads the file once. */
    fun load(): Loudness {
        if (!loaded) {
            loudness = runCatching { store.load() }.getOrNull() ?: Loudness()
            loaded = true
        }
        return loudness
    }

    fun removeAllUnder(folderPath: String) {
        loudness = loudness.removeAllUnder(folderPath)
        runCatching { store.save(loudness) }
    }

    fun pending(tracks: List<Track>): List<Track> = tracks.filter { Loudness.needsMeasuring(it) && !loudness.isFresh(it) }

    /** Measures the pending tracks; [progress] gets the count after every
     *  file and the measurements every few files and at the end. Returns
     *  when done or cancelled. */
    suspend fun run(tracks: List<Track>, progress: (Progress, Loudness?) -> Unit): Loudness {
        load()
        val pending = pending(tracks)
        val total = pending.size
        if (total == 0) return loudness
        var done = 0
        var unsaved = 0
        try {
            withContext(dispatcher) {
                coroutineScope {
                    val results = Channel<Pair<Track, TrackLoudness?>>(Channel.UNLIMITED)
                    val semaphore = Semaphore(concurrency)
                    launch {
                        for (track in pending) {
                            semaphore.acquire()
                            launch {
                                try {
                                    results.send(track to measureOrNull(track))
                                } finally {
                                    semaphore.release()
                                }
                            }
                        }
                    }
                    repeat(total) {
                        val (track, result) = results.receive()
                        if (result != null) { loudness = loudness.set(result, track); unsaved++ }
                        done++
                        if (unsaved >= 10) {
                            runCatching { store.save(loudness) }
                            unsaved = 0
                            progress(Progress(done, total), loudness)
                        } else {
                            progress(Progress(done, total), null)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            if (unsaved > 0) runCatching { store.save(loudness) }
            throw e
        }
        if (unsaved > 0) runCatching { store.save(loudness) }
        progress(Progress(done, total), loudness)
        return loudness
    }

    /** null when the file fails or takes longer than it plays. */
    private suspend fun measureOrNull(track: Track): TrackLoudness? {
        val limit = max(minTimeoutMillis, (track.duration * 1000).toLong())
        return try {
            withTimeoutOrNull(limit) { measure(track) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
