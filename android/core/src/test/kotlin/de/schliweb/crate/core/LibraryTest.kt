// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryTest {
    private val dir = TemporaryDirectory()
    private val reader = FakeMetadataReader()
    private val storeFile = File(dir.dir, "state/library.json")
    private val library = Library(JsonLibraryStore(storeFile), reader, LocalFileSource(), concurrency = 2)
    private val music = File(dir.dir, "music")
    private val folder = LocalFileSource.folder(music)

    @AfterTest
    fun cleanUp() = dir.delete()

    @Test
    fun firstScanAddsEverythingWithTitleFallback() = runTest {
        val a = dir.file("music/a.mp3")
        val b = dir.file("music/b.m4a")
        dir.file("music/notes.txt")
        dir.file("music/.hidden/c.mp3")
        reader.set(a, TrackMetadata(title = " Real Title ", artist = "X", duration = 12.0))

        val summary = library.addFolder(folder)

        assertEquals(Library.RescanSummary(added = 2), summary)
        assertEquals(listOf("Real Title", "b"), library.tracks.map { it.title })
        assertEquals("X", library.tracks[0].artist)
        assertEquals(12.0, library.tracks[0].duration)
        assertEquals(b.path, library.tracks[1].path)
        assertEquals(listOf(folder), library.folders)
    }

    @Test
    fun rescanSkipsUnchangedUpdatesChangedAndDropsMissing() = runTest {
        dir.file("music/a.mp3")
        val b = dir.file("music/b.mp3")
        val c = dir.file("music/c.mp3")
        library.addFolder(folder)
        reader.reads.clear()

        // Same content, same size: must not be re-read.
        var summary = library.rescan()
        assertEquals(Library.RescanSummary(unchanged = 3), summary)
        assertTrue(reader.reads.isEmpty())

        // b changes size, c disappears, d appears.
        b.writeText("longer")
        c.delete()
        val d = dir.file("music/d.mp3")
        reader.set(b, TrackMetadata(title = "B2"))
        summary = library.rescan()
        assertEquals(Library.RescanSummary(added = 1, updated = 1, removed = 1, unchanged = 1), summary)
        assertEquals(setOf(b.path, d.path), reader.reads.toSet())
        assertEquals(listOf("a", "B2", "d"), library.tracks.map { it.title })
    }

    @Test
    fun unreadableFilesAreCountedAndKeepTheirOldEntry() = runTest {
        val a = dir.file("music/a.mp3")
        val bad = dir.file("music/bad.mp3")
        reader.fail(bad)
        var summary = library.addFolder(folder)
        assertEquals(Library.RescanSummary(added = 1, failedFiles = listOf(bad.path)), summary)
        assertEquals(listOf(a.path), library.tracks.map { it.path })

        // A file that turns unreadable keeps its previous entry.
        reader.fail(a)
        a.writeText("changed")
        summary = library.rescan()
        assertEquals(Library.RescanSummary(unchanged = 0, failedFiles = listOf(a.path, bad.path)), summary)
        assertEquals(listOf(a.path), library.tracks.map { it.path })
    }

    @Test
    fun stateSurvivesARestart() = runTest {
        val a = dir.file("music/a.mp3")
        reader.set(a, TrackMetadata(title = "Kept", year = 1999, trackNumber = 3))
        library.addFolder(folder)

        val reopened = Library(JsonLibraryStore(storeFile), reader, LocalFileSource())
        reopened.load()
        assertEquals(library.tracks, reopened.tracks)
        assertEquals(library.folders, reopened.folders)
        assertEquals(1999, reopened.tracks[0].year)
        reader.reads.clear()
        assertEquals(Library.RescanSummary(unchanged = 1), reopened.rescan())
        assertTrue(reader.reads.isEmpty())
    }

    @Test
    fun removeFolderDropsItsTracks() = runTest {
        dir.file("music/a.mp3")
        val other = File(dir.dir, "other")
        dir.file("other/b.mp3")
        library.addFolder(folder)
        library.addFolder(LocalFileSource.folder(other))
        assertEquals(2, library.tracks.size)
        assertEquals(1, library.removeFolder(folder))
        assertEquals(listOf("b"), library.tracks.map { it.title })
        assertEquals(1, library.folders.size)
        assertNull(library.track(File(music, "a.mp3").path))
    }

    @Test
    fun missingFolderThrows() = runTest {
        assertFailsWith<java.io.FileNotFoundException> { library.addFolder(folder) }
        // The folder stays registered; a share that is offline is not forgotten.
        assertEquals(listOf(folder), library.folders)
    }

    @Test
    fun loadWithoutSavedStateIsEmpty() = runTest {
        library.load()
        assertTrue(library.tracks.isEmpty())
        assertTrue(library.folders.isEmpty())
    }

    @Test
    fun progressCountsFilesRead() = runTest {
        dir.file("music/a.mp3")
        dir.file("music/b.mp3")
        val seen = mutableListOf<Pair<Int, Int>>()
        library.addFolder(folder) { read, total -> synchronized(seen) { seen.add(read to total) } }
        assertEquals(0 to 2, seen.first())
        assertEquals(2 to 2, seen.last())
    }
}
