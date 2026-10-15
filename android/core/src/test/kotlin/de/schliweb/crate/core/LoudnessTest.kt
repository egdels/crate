// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoudnessTest {
    private val dir = TemporaryDirectory()

    @AfterTest
    fun cleanUp() = dir.delete()

    private fun sine(dBFS: Double, seconds: Double, sampleRate: Double, channels: Int = 2): Array<FloatArray> {
        val amplitude = 10.0.pow(dBFS / 20)
        val frames = (seconds * sampleRate).toInt()
        val one = FloatArray(frames) { (amplitude * sin(2 * PI * 1000 * it / sampleRate)).toFloat() }
        return Array(channels) { one }
    }

    @Test
    fun aMinus23dBFSSineReadsMinus23LUFS() {
        for (rate in listOf(48_000.0, 44_100.0, 96_000.0)) {
            val meter = LoudnessMeter(rate, 2)
            meter.add(sine(-23.0, 5.0, rate))
            val result = meter.result!!
            assertTrue(abs(result.loudness!! - -23) < 0.1, "at $rate: ${result.loudness}")
            assertTrue(abs(result.peak - 10.0.pow(-23.0 / 20)) < 0.001)
            assertTrue(abs(result.gain!! - 5) < 0.1)
        }
    }

    @Test
    fun samplesArriveInAnyChunking() {
        val signal = sine(-20.0, 3.0, 48_000.0)
        val whole = LoudnessMeter(48_000.0, 2).also { it.add(signal) }
        val pieces = LoudnessMeter(48_000.0, 2)
        var offset = 0
        while (offset < signal[0].size) {
            val end = minOf(signal[0].size, offset + 1234)
            pieces.add(Array(2) { signal[it].copyOfRange(offset, end) })
            offset = end
        }
        assertTrue(abs(whole.result!!.loudness!! - pieces.result!!.loudness!!) < 0.01)
    }

    @Test
    fun silenceAndQuietPassagesAreGatedOut() {
        val meter = LoudnessMeter(48_000.0, 2)
        meter.add(Array(2) { FloatArray(48_000 * 5) })
        assertNull(meter.result)
        meter.add(sine(-23.0, 10.0, 48_000.0))
        meter.add(sine(-60.0, 10.0, 48_000.0))
        assertTrue(abs(meter.result!!.loudness!! - -23) < 0.3)
    }

    @Test
    fun albumGainIsGatedOverAllTracks() {
        val loud = LoudnessMeter(48_000.0, 2).also { it.add(sine(-20.0, 6.0, 48_000.0)) }.result!!
        val quiet = LoudnessMeter(48_000.0, 2).also { it.add(sine(-26.0, 6.0, 48_000.0)) }.result!!
        val together = LoudnessMeter(48_000.0, 2).also { it.add(sine(-20.0, 6.0, 48_000.0)); it.add(sine(-26.0, 6.0, 48_000.0)) }.result!!
        val album = TrackLoudness.albumGain(listOf(loud, quiet))!!
        assertTrue(abs(album - together.gain!!) < 0.05)
        assertTrue(album > loud.gain!! && album < quiet.gain!!)
    }

    @Test
    fun storeRoundTripKeepsGainsToHundredths() {
        val track = makeTrack("a")
        val measured = LoudnessMeter(48_000.0, 2).also { it.add(sine(-23.0, 2.0, 48_000.0)) }.result!!
        val store = JsonLoudnessStore(dir.file("loudness.json").also { it.delete() })
        assertNull(store.load())
        store.save(Loudness().set(measured, track))
        val loaded = store.load()!!
        assertEquals(1, loaded.size)
        assertTrue(abs(loaded.trackGain(track)!! - measured.gain!!) < 0.01)
        assertNull(loaded.measurement(track.copy(modified = 99)))
    }

    @Test
    fun runMeasuresWhatIsPendingAndStopsWhenCancelled() = runBlocking {
        val tracks = (0 until 25).map { makeTrack("t$it") }
        val tagged = makeTrack("tagged").copy(albumGain = 0.0)
        assertFalse(Loudness.needsMeasuring(tagged))
        val store = JsonLoudnessStore(dir.file("loudness.json").also { it.delete() })
        val analyzer = LoudnessAnalyzer(store, measure = { track ->
            TrackLoudness(if (track.title.startsWith("t1")) -10.0 else -20.0, 0.1, LoudnessHistogram())
        }, concurrency = 3)
        var last = LoudnessAnalyzer.Progress(0, 0)
        val result = analyzer.run(tracks + tagged) { progress, _ -> last = progress }
        assertEquals(LoudnessAnalyzer.Progress(25, 25), last)
        assertEquals(25, result.size)
        assertEquals(2.0, result.trackGain(tracks[0]))
        assertEquals(-8.0, result.trackGain(tracks[10]))
        assertNull(result.measurement(tagged))
        assertEquals(25, store.load()!!.size)
        assertTrue(analyzer.pending(tracks + tagged).isEmpty())

        val slowStore = JsonLoudnessStore(dir.file("slow.json").also { it.delete() })
        val slow = LoudnessAnalyzer(slowStore, measure = { delay(20); TrackLoudness(-20.0, 0.1, LoudnessHistogram()) }, concurrency = 2)
        val job = launch { slow.run((0 until 40).map { makeTrack("s$it") }) { _, _ -> } }
        delay(150)
        job.cancel()
        job.join()
        val partial = slowStore.load()
        assertNotNull(partial)
        assertTrue(partial.size in 1 until 40)
    }

    @Test
    fun progressCountsFilesAsTheyFinishNotInOrder() = runBlocking {
        // The first file is slow: with two in flight, the others pass
        // through the second slot and the count moves before it is done.
        val store = JsonLoudnessStore(dir.file("order.json").also { it.delete() })
        val analyzer = LoudnessAnalyzer(store, measure = { track ->
            delay(if (track.title == "slow") 400 else 5)
            TrackLoudness(-20.0, 0.1, LoudnessHistogram())
        }, concurrency = 2)
        val tracks = listOf(makeTrack("slow")) + (0 until 10).map { makeTrack("fast$it") }
        var last = LoudnessAnalyzer.Progress(0, 0)
        val job = launch { analyzer.run(tracks) { progress, _ -> last = progress } }
        delay(200)
        assertTrue(last.done in 1 until 11, "counted ${last.done} while the slow file was still running")
        job.join()
        assertEquals(LoudnessAnalyzer.Progress(11, 11), last)
        assertEquals(11, store.load()!!.size)
    }

    @Test
    fun aFileThatTakesLongerThanItPlaysIsGivenUp() = runBlocking {
        val store = JsonLoudnessStore(dir.file("timeout.json").also { it.delete() })
        val analyzer = LoudnessAnalyzer(store, measure = { track ->
            if (track.title == "stuck") delay(10_000)
            TrackLoudness(-20.0, 0.1, LoudnessHistogram())
        }, concurrency = 2, minTimeoutMillis = 50)
        val tracks = listOf(makeTrack("stuck").copy(duration = 0.01)) + (0 until 3).map { makeTrack("ok$it") }
        var last = LoudnessAnalyzer.Progress(0, 0)
        val result = analyzer.run(tracks) { progress, _ -> last = progress }
        assertEquals(LoudnessAnalyzer.Progress(4, 4), last)
        assertEquals(3, result.size)
        assertNull(result.measurement(tracks[0]))
        assertEquals(listOf(tracks[0]), analyzer.pending(tracks))
    }
}
