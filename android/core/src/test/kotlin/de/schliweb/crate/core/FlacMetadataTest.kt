// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FlacMetadataTest {
    @Test
    fun readsCommentsDurationAndCover() {
        val png = ByteArray(64) { it.toByte() }
        val bytes = flacBytes(
            listOf("TITLE=My Baby", "artist=Ada Lark", "ALBUM=Songs", "TRACKNUMBER=3/12", "DATE=2010-05-01", "DISCNUMBER=1"),
            picture = png,
        )
        val flac = FlacMetadata.read(ByteArrayInputStream(bytes))
        assertEquals("My Baby", flac.value("title"))
        assertEquals("Ada Lark", flac.value("ARTIST"))
        assertTrue(kotlin.math.abs(flac.duration - 90) < 0.001)
        val cover = assertNotNull(flac.cover)
        assertEquals(3, cover.type)
        assertContentEquals(png, cover.data)

        val meta = flac.toTrackMetadata()
        assertEquals("My Baby", meta.title)
        assertEquals("Songs", meta.album)
        assertEquals(3, meta.trackNumber)
        assertEquals(12, meta.trackTotal)
        assertEquals(2010, meta.year)
        assertEquals(1, meta.discNumber)
        assertTrue(kotlin.math.abs(meta.duration - 90) < 0.001)
    }

    @Test
    fun totalsComeFromSeparateKeysToo() {
        val flac = FlacMetadata.read(ByteArrayInputStream(flacBytes(listOf("TITLE=Parsed", "ALBUMARTIST=Band", "TOTALTRACKS=9"))))
        val meta = flac.toTrackMetadata()
        assertEquals("Parsed", meta.title)
        assertEquals("Band", meta.albumArtist)
        assertEquals(9, meta.trackTotal)
    }

    @Test
    fun notAFlacFileThrows() {
        assertFailsWith<FlacMetadata.NotFlac> { FlacMetadata.read(ByteArrayInputStream("not flac at all".toByteArray())) }
        assertFailsWith<FlacMetadata.NotFlac> { FlacMetadata.read(ByteArrayInputStream(ByteArray(0))) }
    }

    @Test
    fun truncatedBlockIsNotAnError() {
        val bytes = flacBytes(listOf("TITLE=Cut"))
        val flac = FlacMetadata.read(ByteArrayInputStream(bytes.copyOf(bytes.size - 3)))
        assertTrue(flac.duration > 0)
    }
}
