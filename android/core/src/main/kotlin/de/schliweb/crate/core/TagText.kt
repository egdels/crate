// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

/** Parsing of the text forms tags come in, shared by every reader. */
object TagText {
    fun int(text: String): Int? = text.trim().toIntOrNull()

    /** "3/12" or "3": the number and, when given, the total. */
    fun numberAndTotal(text: String): Pair<Int?, Int?> {
        val parts = text.split('/')
        return int(parts[0]) to parts.getOrNull(1)?.let(::int)
    }

    /**
     * First run of four digits that is a plausible year: handles "1997",
     * "1997-03-12", "1997-03-12T00:00:00Z" and the like, and rejects
     * "0805" (an ID3 TDAT day-and-month) or "1741" (a TIME) rather than
     * filing an album under the year 805.
     */
    fun year(text: String): Int? {
        val digits = StringBuilder()
        for (character in text) {
            if (character.isDigit()) {
                digits.append(character)
                if (digits.length == 4) {
                    val year = digits.toString().toInt()
                    if (year in 1850..2100) return year
                    digits.clear()
                }
            } else {
                digits.clear()
            }
        }
        return null
    }

    /** Plain text, or ID3v2.3's "(17)" / "17" referring to the ID3v1
     *  list (0-based). */
    fun genre(text: String): String {
        val trimmed = text.trim()
        val digits = trimmed.trim('(', ')')
        val index = digits.toIntOrNull()
        if (index != null) Id3v1Genres.name(index)?.let { return it }
        return trimmed
    }
}

/**
 * The numbered genre list from ID3v1, including the Winamp extensions,
 * which M4A files reference by index in their binary `gnre` atom
 * (1-based) and old ID3v2 files as "(17)" in TCON (0-based).
 */
object Id3v1Genres {
    fun name(index: Int): String? = NAMES.getOrNull(index)

    private val NAMES = listOf(
        "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal",
        "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial",
        "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk",
        "Fusion", "Trance", "Classical", "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise",
        "Alternative Rock", "Bass", "Soul", "Punk", "Space", "Meditative", "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic",
        "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta",
        "Top 40", "Christian Rap", "Pop/Funk", "Jungle", "Native American", "Cabaret", "New Wave", "Psychedelic", "Rave", "Showtunes",
        "Trailer", "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll", "Hard Rock",
        "Folk", "Folk-Rock", "National Folk", "Swing", "Fast Fusion", "Bebop", "Latin", "Revival", "Celtic", "Bluegrass",
        "Avantgarde", "Gothic Rock", "Progressive Rock", "Psychedelic Rock", "Symphonic Rock", "Slow Rock", "Big Band", "Chorus", "Easy Listening", "Acoustic",
        "Humour", "Speech", "Chanson", "Opera", "Chamber Music", "Sonata", "Symphony", "Booty Bass", "Primus", "Porn Groove",
        "Satire", "Slow Jam", "Club", "Tango", "Samba", "Folklore", "Ballad", "Power Ballad", "Rhythmic Soul", "Freestyle",
        "Duet", "Punk Rock", "Drum Solo", "A cappella", "Euro-House", "Dance Hall", "Goa", "Drum & Bass", "Club-House", "Hardcore",
        "Terror", "Indie", "BritPop", "Negerpunk", "Polsk Punk", "Beat", "Christian Gangsta Rap", "Heavy Metal", "Black Metal", "Crossover",
        "Contemporary Christian", "Christian Rock", "Merengue", "Salsa", "Thrash Metal", "Anime", "JPop", "Synthpop",
    )
}
