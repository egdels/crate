// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

import java.io.File
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PracticeSettingsTest {
    private val dir = TemporaryDirectory()

    @AfterTest
    fun cleanUp() = dir.delete()

    @Test
    fun valuesAreClamped() {
        val settings = PracticeSettings(tempo = 5.0, semitones = -20, cents = 80)
        assertEquals(2.0, settings.tempo)
        assertEquals(-12, settings.semitones)
        assertEquals(50, settings.cents)
        assertEquals(0.5, PracticeSettings(tempo = 0.1).tempo)
    }

    @Test
    fun pitchCombinesSemitonesAndCents() {
        assertEquals(-185, PracticeSettings(semitones = -2, cents = 15).pitchCents)
        assertTrue(abs(PracticeSettings(semitones = 12).pitchFactor - 2f) < 1e-6f)
        assertTrue(abs(PracticeSettings(semitones = -12).pitchFactor - 0.5f) < 1e-6f)
        assertFalse(PracticeSettings.NEUTRAL.changesSound)
        assertTrue(PracticeSettings(cents = 1).changesSound)
        assertFalse(PracticeSettings(loop = PracticeLoop.of(1.0, 2.0)).changesSound)
    }

    @Test
    fun referencePitchFollowsTheCents() {
        assertEquals(440.0, PracticeSettings.NEUTRAL.referencePitch)
        assertTrue(abs(PracticeSettings(cents = -32).referencePitch - 432) < 0.1)
        assertTrue(abs(PracticeSettings(cents = 8).referencePitch - 442) < 0.1)
    }

    @Test
    fun resetKeepsTheLoop() {
        val loop = PracticeLoop.of(3.0, 7.0)
        val settings = PracticeSettings(0.75, 2, -10, loop).resetSound()
        assertEquals(PracticeSettings(loop = loop), settings)
    }

    @Test
    fun aSwitchedOffLoopIsKeptButDoesNotPlay() {
        val loop = PracticeLoop.of(2.0, 6.0)
        val off = PracticeSettings(loop = loop, loopOff = true)
        assertEquals(loop, off.loop)
        assertNull(off.activeLoop)
        assertFalse(off.changesPlayback)
        assertFalse(off.isNeutral, "the loop is still remembered")
        assertEquals(loop, off.copy(loopOff = false).activeLoop)
        assertTrue(off.copy(loopOff = false).changesPlayback)
        assertFalse(off.copy(loop = null).loopOff, "no loop, nothing to switch off")
        assertTrue(off.copy(loop = null).isNeutral)
        assertTrue(off.resetSound().loopOff, "reset keeps it off")
    }

    @Test
    fun loopSwapsReversedPointsAndRefusesTinyOnes() {
        val loop = PracticeLoop.of(9.0, 4.0)!!
        assertEquals(4.0, loop.start)
        assertEquals(9.0, loop.end)
        assertTrue(4.0 in loop && 8.9 in loop && 9.0 !in loop)
        assertNull(PracticeLoop.of(1.0, 1.1))
        assertEquals(0.0, PracticeLoop.of(-1.0, 2.0)?.start)
    }

    @Test
    fun loopIsFittedIntoAShorterFile() {
        val loop = PracticeLoop.of(10.0, 20.0)!!
        assertEquals(PracticeLoop.of(10.0, 15.0), loop.clampedTo(15.0))
        assertNull(loop.clampedTo(5.0))
        assertEquals(loop, loop.clampedTo(0.0))
    }

    @Test
    fun bookKeepsOnlyChangedTracksAndForgetsFolders() {
        var book = PracticeBook()
            .with("primary:Music/two/a.mp3", PracticeSettings(semitones = 1))
            .with("primary:Music/twofold/b.mp3", PracticeSettings(semitones = 2))
            .with("primary:Music/c.mp3", PracticeSettings.NEUTRAL)
        assertEquals(2, book.size)
        assertEquals(PracticeSettings.NEUTRAL, book["primary:Music/c.mp3"])
        book = book.removeAllUnder("primary:Music/two")
        assertEquals(setOf("primary:Music/twofold/b.mp3"), book.entries.keys)
        assertEquals(0, book.with("primary:Music/twofold/b.mp3", PracticeSettings.NEUTRAL).size)
    }

    @Test
    fun aBooksTempoReachesItsSpokenTracksOnly() {
        fun track(name: String, genre: String) =
            Track(uri = "content://$name", path = "primary:Books/$name.mp3", fileSize = 1, modified = 0, title = name, genre = genre, duration = 600.0)
        val one = track("01", "Hörbuch")
        val two = track("02", "Hörbuch")
        val song = track("song", "Pop")
        val loop = PracticeLoop.of(1.0, 5.0)
        var book = PracticeBook().with(two.id, PracticeSettings(semitones = 1, loop = loop))
        book = book.withTempo(1.25, listOf(one, two, song))
        assertEquals(PracticeSettings(tempo = 1.25), book[one.id])
        assertEquals(PracticeSettings(tempo = 1.25, semitones = 1, loop = loop), book[two.id])
        assertEquals(PracticeSettings.NEUTRAL, book[song.id])
        assertEquals(1, book.withTempo(1.0, listOf(one, two, song)).size)
    }

    @Test
    fun storeRoundTripsAndClampsWhatItReads() {
        val store = JsonPracticeStore(File(dir.dir, "deep/practice.json"))
        assertNull(store.load())
        val book = PracticeBook()
            .with("a", PracticeSettings(0.7, -3, 12, PracticeLoop.of(1.0, 5.0)))
            .with("b", PracticeSettings(cents = -32))
        store.save(book)
        assertEquals(book, store.load())
        assertTrue(store.file.readText().contains("\"version\": 1"))

        assertFalse(store.file.readText().contains("loopOff"), "written only when set")
        store.save(book.with("a", book["a"].copy(loopOff = true)))
        assertTrue(store.load()!!["a"].loopOff)
        // As the Mac writes it.
        store.file.writeText("""{"version":1,"tracks":{"/y.mp3":{"tempo":1,"semitones":0,"cents":0,"loop":{"start":1,"end":5},"loopOff":true}}}""")
        assertNull(store.load()!!["/y.mp3"].activeLoop)

        // The Mac's file reads the same, and nonsense is clamped.
        store.file.writeText("""{"version":1,"tracks":{"/x.mp3":{"tempo":9,"semitones":0,"loop":{"start":3,"end":3.05}}}}""")
        assertEquals(PracticeSettings(tempo = 2.0), store.load()!!["/x.mp3"])
    }
}

class WaveformTest {
    @Test
    fun peaksFollowTheLevelOverTime() {
        val builder = WaveformBuilder(1000.0, 2)
        // One second quiet, one loud, fed in odd-sized pieces.
        val quiet = Array(2) { FloatArray(700) { i -> if (i % 2 == 0) 0.25f else -0.25f } }
        val loud = Array(2) { c -> FloatArray(700) { if (c == 1) -0.75f else 0.5f } }
        builder.add(quiet, 700)
        builder.add(quiet, 300)
        builder.add(loud, 700)
        builder.add(loud, 300)
        val waveform = builder.result(slices = 100)
        assertEquals(100, waveform.peaks.size)
        assertEquals(2.0, waveform.duration)
        assertTrue(waveform.peaks.take(50).all { abs(it - 0.25f) < 1e-6f })
        assertTrue(waveform.peaks.drop(50).all { abs(it - 0.75f) < 1e-6f })
        assertEquals(0.75f, waveform.maximum)
        // A constant level, or a square wave, has that level as its RMS.
        assertTrue(waveform.levels.take(50).all { abs(it - 0.25f) < 1e-6f })
        assertTrue(waveform.levels.drop(50).all { abs(it - 0.6374f) < 1e-3f }) // root of the mean of 0.5² and 0.75²
        assertTrue(abs(waveform.maximumLevel - 0.6374f) < 1e-3f)
    }

    @Test
    fun levelsTellLoudFromQuietWhereThePeaksDoNot() {
        val builder = WaveformBuilder(1000.0, 1)
        // Full-scale clicks on a quiet bed, then on a loud one.
        val quiet = arrayOf(FloatArray(1000) { if (it % 10 == 0) 1f else 0.05f })
        val loud = arrayOf(FloatArray(1000) { if (it % 10 == 0) 1f else 0.6f })
        builder.add(quiet, 1000)
        builder.add(loud, 1000)
        val waveform = builder.result(slices = 20)
        assertTrue(waveform.peaks.all { it == 1f })
        assertTrue(waveform.levels.take(10).all { it < 0.35f })
        assertTrue(waveform.levels.drop(10).all { it > 0.6f })
    }

    @Test
    fun aShortFileGetsNoMoreSlicesThanBlocks() {
        val builder = WaveformBuilder(1000.0, 1)
        builder.add(arrayOf(FloatArray(25) { 1f }), 25)
        val waveform = builder.result(slices = 1000)
        assertEquals(3, waveform.peaks.size)
        assertTrue(waveform.peaks.all { it == 1f })
        assertTrue(WaveformBuilder(1000.0, 1).result().maximum > 0f)
    }
}

class FileWaveformCacheTest {
    private val dir = TemporaryDirectory()

    @AfterTest
    fun cleanUp() = dir.delete()

    private fun waveform() = Waveform(FloatArray(1000) { it / 999f }, FloatArray(1000) { 1 - it / 999f }, 228.5)

    @Test
    fun roundTripsWithinAQuantizationStep() {
        val cache = FileWaveformCache(File(dir.dir, "waves"))
        val track = makeTrack("a")
        assertNull(cache.get(track))
        cache.put(track, waveform())
        val read = cache.get(track)!!
        assertEquals(228.5, read.duration)
        assertEquals(1000, read.levels.size)
        for (i in 0 until 1000) {
            assertTrue(abs(read.peaks[i] - i / 999f) <= 0.5f / 255 + 1e-6f)
            assertTrue(abs(read.levels[i] - (1 - i / 999f)) <= 0.5f / 255 + 1e-6f)
        }
    }

    @Test
    fun aChangedFileIsNotServed() {
        val cache = FileWaveformCache(File(dir.dir, "waves"))
        val track = makeTrack("a")
        cache.put(track, waveform())
        assertNull(cache.get(track.copy(modified = track.modified + 1)))
        assertNull(cache.get(track.copy(fileSize = track.fileSize + 1)))
        assertNull(cache.get(makeTrack("b")))
    }

    @Test
    fun aDamagedFileIsIgnored() {
        val directory = File(dir.dir, "waves")
        val cache = FileWaveformCache(directory)
        val track = makeTrack("a")
        cache.put(track, waveform())
        directory.listFiles()!!.single().writeBytes(byteArrayOf(1, 2, 3))
        assertNull(cache.get(track))
    }
}
