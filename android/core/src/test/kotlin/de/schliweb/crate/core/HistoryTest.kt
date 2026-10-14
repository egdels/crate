// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HistoryTest {
    private val dir = TemporaryDirectory()
    private val a = makeTrack("a", album = "One")
    private val b = makeTrack("b", album = "One")
    private val c = makeTrack("c", album = "Two", folder = "/music/two")

    @AfterTest
    fun cleanUp() = dir.delete()

    @Test
    fun recordPutsTheNewestFirstOnce() {
        val history = History().record(a.id).record(b.id).record(a.id)
        assertEquals(listOf(a.id, b.id), history.ids)
        assertEquals(listOf(a, b), history.tracks(listOf(a, b, c)))
    }

    @Test
    fun recordIsCapped() {
        var history = History()
        for (i in 0 until History.LIMIT + 10) history = history.record("t$i")
        assertEquals(History.LIMIT, history.ids.size)
        assertEquals("t${History.LIMIT + 9}", history.ids.first())
    }

    @Test
    fun tracksSkipGoneIdsAndRemoveAllPrunesAFolder() {
        val history = History(listOf("/elsewhere/gone.mp3", c.id, a.id))
        assertEquals(listOf(c, a), history.tracks(listOf(a, b, c)))
        assertEquals(listOf("/elsewhere/gone.mp3", a.id), history.removeAllUnder("/music/two").ids)
    }

    @Test
    fun storeRoundTrip() {
        val store = JsonHistoryStore(dir.file("history.json").also { it.delete() })
        assertNull(store.load())
        val history = History().record(b.id).record(a.id)
        store.save(history)
        assertEquals(history, JsonHistoryStore(store.file).load())
    }

    @Test
    fun recentlyAddedIsNewestDayFirstKeepingAlbumOrderWithinADay() {
        val day = 86_400_000L
        val old1 = makeTrack("old1").copy(modified = day + 10 * 3_600_000)
        val old2 = makeTrack("old2").copy(modified = day + 9 * 3_600_000)
        val new1 = makeTrack("new1").copy(modified = 5 * day + 3_600_000)
        val new2 = makeTrack("new2").copy(modified = 5 * day + 23 * 3_600_000)
        assertEquals(listOf("new1", "new2", "old1"), recentlyAdded(listOf(old1, old2, new1, new2), limit = 3).map { it.title })
    }
}
