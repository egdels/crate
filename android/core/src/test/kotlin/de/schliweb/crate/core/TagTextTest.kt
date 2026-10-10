// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TagTextTest {
    @Test
    fun yearTakesTheFirstPlausibleFourDigits() {
        assertEquals(1997, TagText.year("1997"))
        assertEquals(1997, TagText.year("1997-03-12T00:00:00Z"))
        assertNull(TagText.year("0805"))
        assertNull(TagText.year("1741"))
        assertEquals(2004, TagText.year("12/2004"))
    }

    @Test
    fun numberAndTotal() {
        assertEquals(3 to 12, TagText.numberAndTotal("3/12"))
        assertEquals(3 to null, TagText.numberAndTotal("3"))
        assertEquals(null to 12, TagText.numberAndTotal("/12"))
    }

    @Test
    fun genreResolvesId3v1Indexes() {
        assertEquals("Rock", TagText.genre("(17)"))
        assertEquals("Rock", TagText.genre("17"))
        assertEquals("Shoegaze", TagText.genre(" Shoegaze "))
        assertEquals("Synthpop", Id3v1Genres.name(147))
        assertNull(Id3v1Genres.name(148))
    }

    @Test
    fun formatting() {
        assertEquals("3:38", timeText(218.9))
        assertEquals("1:02:15", timeText(3735.0))
        assertEquals("0:00", timeText(Double.NaN))
        assertEquals("1 hr 12 min", lengthText(4320.0))
        assertEquals("3 min", lengthText(190.0))
        assertEquals("1 min", lengthText(5.0))
        assertEquals("1 track", countText(1, "track"))
        assertEquals("2,020 tracks", countText(2020, "track"))
    }

    @Test
    fun folderImagesPreferCoverThenNameThenSize() {
        data class Img(val name: String, val size: Long)
        val images = listOf(Img("scan-back.jpg", 900), Img("Cover.JPG", 10), Img("scan-front.jpg", 500), Img("big.png", 1000))
        val ordered = FolderImages.order(images, { it.name }, { it.size })
        assertEquals(listOf("Cover.JPG", "scan-front.jpg", "big.png", "scan-back.jpg"), ordered.map { it.name })
    }
}
