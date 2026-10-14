// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PlaybackStateTest {
    private val dir = TemporaryDirectory()
    private val state = PlaybackState(
        trackIds = listOf("primary:Music/a.mp3", "primary:Music/b.mp3", "primary:Music/c.mp3"),
        asAlbum = true, currentIndex = 1, position = 42.5, isShuffled = true, repeatMode = 2,
    )

    @AfterTest
    fun cleanUp() = dir.delete()

    @Test
    fun nothingSavedIsNull() {
        assertNull(JsonPlaybackStateStore(dir.dir).load())
    }

    @Test
    fun roundTrip() {
        JsonPlaybackStateStore(dir.dir).save(state)
        assertEquals(state, JsonPlaybackStateStore(dir.dir).load())
    }

    @Test
    fun queueFileIsWrittenOnlyWhenTheQueueChanges() {
        val store = JsonPlaybackStateStore(dir.dir)
        store.save(state)
        val later = state.copy(position = 60.0, currentIndex = 2)
        store.save(later)
        assertEquals(1, store.queueWrites)
        val grown = later.copy(trackIds = later.trackIds + "primary:Music/d.mp3")
        store.save(grown)
        assertEquals(2, store.queueWrites)
        assertEquals(grown, store.load())
    }

    @Test
    fun staleIndexIsNull() {
        val store = JsonPlaybackStateStore(dir.dir)
        store.save(state.copy(currentIndex = 7))
        assertNull(store.load())
    }

    @Test
    fun clearRemovesBothFiles() {
        val store = JsonPlaybackStateStore(dir.dir)
        store.save(state)
        store.clear()
        assertFalse(store.queueFile.exists())
        assertFalse(store.positionFile.exists())
        assertNull(store.load())
    }
}
