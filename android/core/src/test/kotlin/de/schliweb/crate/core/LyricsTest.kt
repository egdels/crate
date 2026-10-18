// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricsTest {
    @Test
    fun lrcLinesCarryTheirTimes() {
        val lyrics = Lyrics.parse("[ti:Song]\n[ar:Artist]\n\n[00:00.00]First line\n[00:07.50]Second line\n[00:14:00]Third line\n")
        assertTrue(lyrics.isTimed)
        assertEquals(listOf("First line", "Second line", "Third line"), lyrics.lines.map { it.text })
        assertEquals(listOf(0.0, 7.5, 14.0), lyrics.lines.map { it.time })
    }

    @Test
    fun severalStampsOnOneLineBecomeSeveralLinesInOrder() {
        val lyrics = Lyrics.parse("[00:30.00][00:10.00]Chorus\n[00:20.00]Verse")
        assertEquals(listOf("Chorus", "Verse", "Chorus"), lyrics.lines.map { it.text })
        assertEquals(listOf(10.0, 20.0, 30.0), lyrics.lines.map { it.time })
    }

    @Test
    fun offsetShiftsTheTimes() {
        val lyrics = Lyrics.parse("[offset:-500]\n[00:01.00]a\n[00:02.00]b")
        assertEquals(listOf(0.5, 1.5), lyrics.lines.map { it.time })
    }

    @Test
    fun plainTextKeepsParagraphs() {
        val lyrics = Lyrics.parse("\n\nVerse one\nline two\n\n\n\nChorus\n\n")
        assertFalse(lyrics.isTimed)
        assertEquals(listOf("Verse one", "line two", "", "Chorus"), lyrics.lines.map { it.text })
        assertNull(lyrics.indexAt(10.0))
    }

    @Test
    fun indexFollowsTheTime() {
        val lyrics = Lyrics.parse("[00:05.00]a\n[00:10.00]b\n[00:15.00]c")
        assertNull(lyrics.indexAt(0.0))
        assertEquals(0, lyrics.indexAt(5.0))
        assertEquals(1, lyrics.indexAt(12.3))
        assertEquals(2, lyrics.indexAt(99.0))
    }

    @Test
    fun emptyTextIsEmpty() {
        assertTrue(Lyrics.parse("").isEmpty)
        assertTrue(Lyrics.parse("[ti:only a header]").isEmpty)
    }
}
