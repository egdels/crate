// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BookmarksTest {
    private val dir = TemporaryDirectory()

    @AfterTest
    fun cleanUp() = dir.delete()

    private fun chapter(name: String, folder: String = "primary:Books/One", duration: Double = 600.0) =
        Track(uri = "content://$folder/$name", path = "$folder/$name.mp3", fileSize = 1, modified = 0, title = name, genre = "Hörbuch", duration = duration)

    private val day = 1_800_000_000_000L

    @Test
    fun onlySpokenOrLongTracksGetAMark() {
        val song = chapter("song").copy(genre = "Pop")
        assertEquals(0, Bookmarks().set(song, 60.0).count)
        val one = chapter("01")
        assertEquals(60.0, Bookmarks().set(one, 60.0).position(one))
    }

    @Test
    fun theVeryStartAndTheEndAreNoPlaceToResume() {
        val one = chapter("01")
        val restarted = Bookmarks().set(one, 300.0).set(one, 3.0)
        assertNull(restarted.position(one))
        assertEquals(0, restarted.count)
        assertNull(Bookmarks().set(one, 595.0).position(one))
        assertEquals(0, Bookmarks().set(one, 300.0).finish(one).count)
    }

    @Test
    fun anOldMarkResumesALittleEarlier() {
        val one = chapter("01")
        val marks = Bookmarks().set(one, 300.0, day)
        assertEquals(300.0, marks.position(one, day + 2_000))
        assertEquals(290.0, marks.position(one, day + 1_800_000))
        assertEquals(280.0, marks.position(one, day + 86_400_000))
    }

    @Test
    fun theAlbumResumesAtTheFileHeardLast() {
        val tracks = listOf(chapter("01"), chapter("02"), chapter("03"))
        assertNull(Bookmarks().resumeIndex(tracks))
        val marks = Bookmarks().set(tracks[2], 100.0, day).set(tracks[1], 200.0, day + 60_000)
        assertEquals(1, marks.resumeIndex(tracks))
        // Stopped just before the end of a file: the next one.
        assertEquals(2, marks.set(tracks[1], 595.0, day + 120_000).resumeIndex(tracks))
    }

    @Test
    fun theOldestMarksGoAndAFolderCanBePruned() {
        var marks = Bookmarks()
        for (i in 0 until Bookmarks.LIMIT + 5) marks = marks.set(chapter("$i"), 60.0, day + i * 1000L)
        assertEquals(Bookmarks.LIMIT, marks.count)
        assertNull(marks.entries[chapter("0").id])
        assertNotNull(marks.entries[chapter("${Bookmarks.LIMIT + 4}").id])
        marks = marks.set(chapter("x", folder = "primary:Books/Two"), 60.0, day + 10_000_000L)
        assertEquals(1, marks.removeAllUnder("primary:Books/One").count)
    }

    @Test
    fun storeRoundTrip() {
        val store = JsonBookmarksStore(dir.file("bookmarks.json").also { it.delete() })
        assertNull(store.load())
        val marks = Bookmarks().set(chapter("01"), 123.5, day)
        store.save(marks)
        assertEquals(marks, JsonBookmarksStore(store.file).load())
    }
}
