// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplayGainTest {
    @Test
    fun parsesTheTextForms() {
        assertEquals(-6.5, ReplayGain.decibels("-6.50 dB"))
        assertEquals(3.25, ReplayGain.decibels("+3.25 dB"))
        assertEquals(-2.5, ReplayGain.decibels(" -2,5 DB "))
        assertEquals(-7.25, ReplayGain.decibels("-7.25"))
        assertNull(ReplayGain.decibels("loud"))
        assertEquals(0.988, ReplayGain.peak("0.988"))
        assertNull(ReplayGain.peak("0"))
        assertNull(ReplayGain.peak("x"))
    }

    @Test
    fun peakLimitsThePositiveGain() {
        // A peak of 0.5 leaves 6 dB of headroom; more than that would clip.
        assertTrue(abs(ReplayGain.limited(9.0, 0.5) - 6.02) < 0.01)
        assertEquals(3.0, ReplayGain.limited(3.0, 0.5))
        assertEquals(-8.0, ReplayGain.limited(-8.0, 0.5))
        assertEquals(9.0, ReplayGain.limited(9.0, null))
        assertEquals(24.0, ReplayGain.limited(40.0, null))
        assertEquals(-24.0, ReplayGain.limited(-40.0, 0.9))
    }

    @Test
    fun soundCheckBecomesDecibelsAndPeak() {
        val loud = assertNotNull(ReplayGain.soundCheck(" 00001070 0000120F 00008A1B 000082AF 00023568 000234CB 00008000 00007E5F 00000000 00000000"))
        assertTrue(abs(loud.first - (-10 * log10(4623.0 / 1000))) < 0.001, "the louder channel, 0x120F")
        assertTrue(abs(loud.second!! - 1.0) < 0.001, "0x8000 of 32768")
        val quiet = assertNotNull(ReplayGain.soundCheck(" 0000018B 0000016F 0000290C 0000264E 00013105 00013105 00006000 00005800 00000000 00000000"))
        assertTrue(abs(quiet.first - 4.03) < 0.01)
        assertTrue(abs(quiet.second!! - 0.75) < 0.001)
        assertNull(ReplayGain.soundCheck("00000000 00000000"))
        assertNull(ReplayGain.soundCheck("nonsense"))
    }

    @Test
    fun replayGainWinsOverSoundCheckAndFallsBackToIt() {
        assertEquals(-6.5 to -7.25, ReplayGain.gains(-6.5, 0.9, -7.25, null, 4.0 to 0.5))
        assertEquals(null to -7.25, ReplayGain.gains(null, null, -7.25, null, 4.0 to 0.5))
        val onlyCheck = ReplayGain.gains(null, null, null, null, 9.0 to 0.5)
        assertTrue(abs(onlyCheck.first!! - 6.02) < 0.01, "limited by the Sound Check peak")
        assertNull(onlyCheck.second)
        assertEquals(null to null, ReplayGain.gains(null, null, null, null, null))
    }

    @Test
    fun anAlbumOfTrackGainsPlaysAtTheirMean() {
        val intro = makeTrack("Speak to Me", album = "Dark Side", track = 1).copy(trackGain = 13.9)
        val money = makeTrack("Money", album = "Dark Side", track = 6).copy(trackGain = -2.8)
        val untagged = makeTrack("Bonus", album = "Dark Side", track = 11)
        val album = listOf(intro, money, untagged)
        val mean = assertNotNull(ReplayGain.albumMean(album))
        assertTrue(abs(mean - 5.55) < 0.001, "untagged tracks do not count")
        assertNull(ReplayGain.albumMean(listOf(untagged)))

        // Listening to the album: every track at the mean, the dynamics
        // between them intact. The untagged one too.
        for (track in album) assertTrue(abs(ReplayGain.playbackGain(track, true, mean) - 5.55) < 0.001)
        // Shuffle or All Tracks: each on its own.
        assertEquals(13.9, ReplayGain.playbackGain(intro, false, mean))
        assertEquals(0.0, ReplayGain.playbackGain(untagged, false, mean))

        // A real album gain beats the mean; alone it also serves the
        // track outside an album when the track gain is missing.
        assertEquals(-7.25, ReplayGain.playbackGain(money.copy(albumGain = -7.25), true, mean))
        assertEquals(-7.25, ReplayGain.playbackGain(untagged.copy(albumGain = -7.25), false, null))
        assertEquals(13.9, ReplayGain.playbackGain(intro, true, null), "no mean, no album gain: the track's own")
    }

    @Test
    fun factorIsTheUsualTwentyLog() {
        assertEquals(1f, ReplayGain.factor(0.0))
        assertTrue(abs(ReplayGain.factor(-6.0206) - 0.5f) < 0.001f)
        assertTrue(abs(ReplayGain.factor(6.0206) - 2f) < 0.001f)
    }
}
