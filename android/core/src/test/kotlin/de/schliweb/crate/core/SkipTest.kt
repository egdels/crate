// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkipTest {
    private fun track(file: String, genre: String? = null, duration: Double = 240.0) =
        Track(uri = "content://books/$file", path = "primary:Books/$file", fileSize = 1, modified = 0, title = file, genre = genre, duration = duration)

    @Test
    fun offeredForSpokenWordAndLongTracks() {
        assertTrue(Skip.isOffered(track("Book.M4B")))
        assertTrue(Skip.isOffered(track("01.mp3", genre = "Hörbuch")))
        assertTrue(Skip.isOffered(track("01.mp3", genre = "HÖRSPIEL")))
        assertTrue(Skip.isOffered(track("01.mp3", genre = "Audiobooks")))
        assertTrue(Skip.isOffered(track("01.mp3", genre = "Spoken Word")))
        assertTrue(Skip.isOffered(track("01.mp3", genre = "Podcast")))
        assertTrue(Skip.isOffered(track("Live Set.mp3", genre = "Techno", duration = 75 * 60.0)))
        assertFalse(Skip.isOffered(track("Song.m4a", genre = "Rock")))
        assertFalse(Skip.isOffered(track("Song.mp3")))
    }

    @Test
    fun jumpsStayInsideTheTrack() {
        assertEquals(Skip.Target.Position(45.0), Skip.target(60.0, -15.0, 300.0))
        assertEquals(Skip.Target.Position(0.0), Skip.target(10.0, -15.0, 300.0))
        assertEquals(Skip.Target.Position(130.0), Skip.target(100.0, 30.0, 300.0))
        assertEquals(Skip.Target.NextTrack, Skip.target(280.0, 30.0, 300.0))
        // Duration not known yet: no next track, just forward.
        assertEquals(Skip.Target.Position(310.0), Skip.target(280.0, 30.0, 0.0))
    }

    @Test
    fun jumpsInsideAPlayingLoopStayInTheLoop() {
        val loop = PracticeLoop.of(40.0, 50.0)
        assertEquals(Skip.Target.Position(40.0), Skip.target(43.0, -5.0, 300.0, loop))
        assertEquals(Skip.Target.Position(49.0), Skip.target(47.0, 5.0, 300.0, loop))
        assertEquals(Skip.Target.Position(47.0), Skip.target(42.0, 5.0, 300.0, loop))
        // Before the loop (its run-in) the jump is free.
        assertEquals(Skip.Target.Position(15.0), Skip.target(20.0, -5.0, 300.0, loop))
        val tiny = PracticeLoop.of(40.0, 40.5)
        assertEquals(Skip.Target.Position(40.0), Skip.target(40.2, 5.0, 300.0, tiny))
    }
}
