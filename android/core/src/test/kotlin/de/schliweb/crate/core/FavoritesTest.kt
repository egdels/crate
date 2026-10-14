// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FavoritesTest {
    private val dir = TemporaryDirectory()
    private val a = makeTrack("a", album = "One")
    private val b = makeTrack("b", album = "One")
    private val c = makeTrack("c", album = "Two", folder = "/music/two")

    @AfterTest
    fun cleanUp() = dir.delete()

    @Test
    fun toggleAddsThenRemoves() {
        var favorites = Favorites()
        assertTrue(favorites.isEmpty)
        favorites = favorites.toggle(a)
        assertTrue(a in favorites)
        assertFalse(b in favorites)
        assertEquals(1, favorites.size)
        favorites = favorites.toggle(a)
        assertFalse(a in favorites)
        assertTrue(favorites.isEmpty)
    }

    @Test
    fun setMarksAndUnmarksSeveralAtOnce() {
        var favorites = Favorites().set(true, listOf(a, b))
        assertTrue(favorites.containsAll(listOf(a, b)))
        assertFalse(favorites.containsAll(listOf(a, b, c)))
        assertFalse(Favorites().containsAll(emptyList()))
        favorites = favorites.set(false, listOf(a))
        assertEquals(setOf(b.id), favorites.ids)
    }

    @Test
    fun filterKeepsOrderAndSkipsUnknown() {
        val favorites = Favorites(setOf("/elsewhere/gone.mp3")).set(true, listOf(c, a))
        assertEquals(listOf(a, c), favorites.filter(listOf(a, b, c)))
        assertEquals(3, favorites.size)
    }

    @Test
    fun removingAFolderDropsOnlyItsIds() {
        var favorites = Favorites().set(true, listOf(a, c, makeTrack("d", folder = "/music2")))
        favorites = favorites.removeAllUnder("/music/two")
        assertEquals(setOf(a.id, "/music2/d.mp3"), favorites.ids)
        favorites = favorites.removeAllUnder("/music/")
        assertEquals(setOf("/music2/d.mp3"), favorites.ids)
    }

    @Test
    fun storeRoundTripsAndLoadsNullWhenMissing() {
        val store = JsonFavoritesStore(File(dir.dir, "deep/favorites.json"))
        assertNull(store.load())

        val favorites = Favorites().set(true, listOf(b, a, c))
        store.save(favorites)
        assertEquals(favorites, store.load())

        // The ids are sorted, so the same set gives the same bytes.
        val text = store.file.readText()
        assertTrue("\"version\": 1" in text, text)
        assertTrue(text.indexOf(a.id) < text.indexOf(b.id))
        assertTrue(text.indexOf(b.id) < text.indexOf(c.id))
        assertFalse(File(store.file.path + ".tmp").exists())
    }

    @Test
    fun unknownKeysAreIgnored() {
        val store = JsonFavoritesStore(dir.file("favorites.json", """{"version": 1, "ids": ["/x.mp3"], "later": true}"""))
        assertEquals(Favorites(setOf("/x.mp3")), store.load())
    }
}
