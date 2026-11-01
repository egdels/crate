// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

/**
 * Tracks grouped by album. Derived from the tracks on demand, never
 * stored: the library's only persistent unit is the [Track].
 *
 * Tags are the primary source, but the folder a file lies in breaks
 * ties, because tags are often half right: a compilation whose tracks
 * carry each artist but no album artist, an album title spelled two
 * ways, a stray ";" behind one track's artist, half an album left
 * untagged. Every one of these would split a folder into several tiles
 * if tags alone decided. So an album is "the tracks in one folder with
 * the same album title", compared loosely, with the artist derived from
 * what the tracks agree on.
 */
data class Album(
    val title: String,
    val artist: String,
    val year: Int?,
    /** The genre most of the tracks carry; null when none is tagged. */
    val genre: String?,
    /** The folder the tracks lie in; disc subfolders ("CD 2") count as
     *  their parent. */
    val folder: String,
    /** Sorted by disc number, then track number, then title. */
    val tracks: List<Track>,
) {
    /** Folder, artist and title: two folders with an identically tagged
     *  album are two albums, which keeps the id unique. */
    val id: String get() = "$folder\u001F$artist\u001F$title"

    /** One disc of the album: its number and its tracks, in order. */
    data class Disc(val number: Int, val tracks: List<Track>)

    /** The tracks by disc, in order; untagged tracks count as disc 1. An
     *  album page shows the disc numbers when there is more than one. */
    val discs: List<Disc>
        get() {
            val discs = mutableListOf<Disc>()
            for (track in tracks) {
                val number = track.discNumber ?: 1
                val last = discs.lastOrNull()
                if (last != null && last.number == number) {
                    discs[discs.size - 1] = Disc(number, last.tracks + track)
                } else {
                    discs += Disc(number, listOf(track))
                }
            }
            return discs
        }

    val duration: Double get() = tracks.sumOf { it.duration }

    /** What the album sorts by: the tracks' sort tags when they have
     *  them ("Beatles, The"), otherwise the name without a leading
     *  "The". Album artist sort tags win over track artist ones, like
     *  the names themselves. */
    val sortArtist: String
        get() = mostCommon(tracks.mapNotNull { it.albumArtistSort.trimmedOrNull() })
            ?: mostCommon(tracks.mapNotNull { it.artistSort.trimmedOrNull() })
            ?: sortName(artist)

    val sortTitle: String
        get() = mostCommon(tracks.mapNotNull { it.albumSort.trimmedOrNull() }) ?: sortName(title)

    companion object {
        const val UNKNOWN_TITLE = "Unknown Album"
        const val UNKNOWN_ARTIST = "Unknown Artist"
        const val VARIOUS_ARTISTS = "Various Artists"

        private data class Key(val folder: String, val title: String)

        private class Bucket(val folder: String, val tracks: MutableList<Track>)

        /**
         * Groups by (folder, loosely compared album title) and sorts
         * albums by artist, then year, then title, or a numbered series
         * by its numbers ([sorted]). [roots] are the
         * watched folders' paths: an untagged album takes its artist from
         * the parent folder's name, unless that parent is a root.
         */
        fun group(tracks: List<Track>, roots: List<String> = emptyList()): List<Album> {
            val buckets = LinkedHashMap<Key, Bucket>()
            for (track in tracks) {
                val folder = groupingFolder(track.path)
                val key = Key(folder.lowercase(), looseKey(track.album ?: ""))
                buckets.getOrPut(key) { Bucket(folder, mutableListOf()) }.tracks.add(track)
            }

            // Untagged tracks in a folder that holds exactly one tagged album
            // belong to that album.
            for ((_, keys) in buckets.keys.toList().groupBy { it.folder }) {
                if (keys.size != 2) continue
                val untagged = keys.firstOrNull { it.title.isEmpty() } ?: continue
                val tagged = keys.firstOrNull { it.title.isNotEmpty() } ?: continue
                buckets.getValue(tagged).tracks.addAll(buckets.getValue(untagged).tracks)
                buckets.remove(untagged)
            }

            // Sibling folders holding the same tagged album by the same artist
            // and named alike but for a number ("soul_1", "soul_2") are its
            // discs: one album, filed under the parent. Differently named
            // siblings with the same tags are left alone; those are copies.
            data class Sibling(val parent: String, val title: String, val artist: String, val stem: String)
            val siblings = buckets.keys.filter { it.title.isNotEmpty() }.groupBy { key ->
                val folder = buckets.getValue(key).folder
                Sibling(
                    parent = Paths.parent(folder).lowercase(),
                    title = key.title,
                    artist = looseKey(artist(buckets.getValue(key).tracks)),
                    stem = discStem(Paths.lastComponent(folder)),
                )
            }
            for ((sibling, keys) in siblings) {
                if (keys.size < 2 || sibling.stem.isEmpty()) continue
                val parent = Paths.parent(buckets.getValue(keys[0]).folder)
                val merged = mutableListOf<Track>()
                for (key in keys) {
                    merged.addAll(buckets.getValue(key).tracks)
                    buckets.remove(key)
                }
                buckets[Key(sibling.parent, sibling.title)] = Bucket(parent, merged)
            }

            val rootPaths = roots.map { it.trimEnd('/').lowercase() }.toSet()
            return buckets.values.map { bucket ->
                val parent = Paths.parent(bucket.folder)
                val insideRoot = bucket.folder.lowercase() !in rootPaths &&
                    parent.lowercase() !in rootPaths && parent.isNotEmpty()
                val parentName = if (insideRoot) Paths.lastComponent(parent).takeIf { it.isNotEmpty() } else null
                Album(
                    title = title(bucket.tracks, bucket.folder),
                    artist = artist(bucket.tracks, parentName),
                    year = bucket.tracks.mapNotNull { it.year }.minOrNull(),
                    genre = mostCommon(bucket.tracks.mapNotNull { it.genre.trimmedOrNull() }),
                    folder = bucket.folder,
                    tracks = bucket.tracks.sortedWith(TRACK_ORDER),
                )
            }.let(::sorted)
        }

        /**
         * Sorted by artist; an artist's albums by year, then title. Unless
         * they form a numbered series ([seriesNumbers]): radio plays and
         * audiobook series carry the year of the edition, not of the
         * episode, so then the number decides, ties by title, then year,
         * and the albums without a number follow by year and title. A
         * port of `Album.sorted` in the Swift core.
         */
        internal fun sorted(albums: List<Album>): List<Album> {
            class Keyed(val album: Album, val artist: String, val title: String)
            val keyed = albums.map { Keyed(it, it.sortArtist, it.sortTitle) }
                .sortedWith { a, b -> naturalCompare(a.artist, b.artist) }
            val result = ArrayList<Album>(albums.size)
            var start = 0
            while (start < keyed.size) {
                var end = start + 1
                while (end < keyed.size && naturalCompare(keyed[end].artist, keyed[start].artist) == 0) end++
                val run = keyed.subList(start, end)
                val numbers = seriesNumbers(run.map { it.title }) ?: List(run.size) { null }
                val byYear = Comparator<Keyed> { a, b ->
                    val y = (a.album.year ?: Int.MAX_VALUE).compareTo(b.album.year ?: Int.MAX_VALUE)
                    if (y != 0) y else naturalCompare(a.title, b.title)
                }
                run.zip(numbers).sortedWith { (a, x), (b, y) ->
                    when {
                        x != null && y != null && x != y -> x.compareTo(y)
                        x != null && y == null -> -1
                        x == null && y != null -> 1
                        x != null -> naturalCompare(a.title, b.title).takeIf { it != 0 }
                            ?: (a.album.year ?: Int.MAX_VALUE).compareTo(b.album.year ?: Int.MAX_VALUE)
                        else -> byYear.compare(a, b)
                    }
                }.mapTo(result) { it.first.album }
                start = end
            }
            return result
        }

        /**
         * The episode number of each title when one artist's titles form
         * a numbered series, else null. A number counts under the word in
         * front of it ("Volume 36", "Folge 194", "Peter Grant 4"); a word
         * is a series word when at least two titles carry it with
         * different numbers; a word and its abbreviation (three letters
         * or more: "Vol." and "Volume") are one word. A series needs
         * three numbered titles and two thirds of all. See the Swift core.
         */
        internal fun seriesNumbers(titles: List<String>): List<Int?>? {
            var found = titles.map(::numberedWords)
            val short = found.flatten().map { it.first }.toSet().filter { it.length >= 3 }.sortedBy { it.length }
            found = found.map { list -> list.map { (word, n) -> (short.firstOrNull { word.startsWith(it) } ?: word) to n } }
            val titlesWith = HashMap<String, Int>()
            val numbersOf = HashMap<String, MutableSet<Int>>()
            for (words in found) {
                for (word in words.map { it.first }.toSet()) titlesWith[word] = (titlesWith[word] ?: 0) + 1
                for ((word, n) in words) numbersOf.getOrPut(word) { HashSet() }.add(n)
            }
            val seriesWords = titlesWith.keys.filter { titlesWith.getValue(it) >= 2 && numbersOf.getValue(it).size >= 2 }.toSet()
            val numbers = found.map { words -> words.firstOrNull { it.first in seriesWords }?.second }
            val numbered = numbers.count { it != null }
            return if (numbered >= 3 && numbered * 3 >= titles.size * 2) numbers else null
        }

        /** Every free-standing number in the title with the word before
         *  it, lowercased ("" at the start). Not: disc numbers ("CD 2"),
         *  digits inside a word, parts of "1.000", over four digits. */
        internal fun numberedWords(title: String): List<Pair<String, Int>> {
            fun isDigit(i: Int) = i in title.indices && title[i] in '0'..'9'
            fun isWordChar(i: Int) = i in title.indices && title[i].isLetterOrDigit()
            val result = ArrayList<Pair<String, Int>>()
            var i = 0
            while (i < title.length) {
                if (!isDigit(i)) { i++; continue }
                val start = i
                while (isDigit(i)) i++
                val end = i
                if (end - start > 4) continue
                if ((start > 0 && title[start - 1].isLetter()) || (end < title.length && title[end].isLetter())) continue
                if (start > 1 && title[start - 1] in ".," && isDigit(start - 2)) continue
                if (end + 1 < title.length && title[end] in ".," && isDigit(end + 1)) continue
                var wordEnd = start - 1
                while (wordEnd >= 0 && !isWordChar(wordEnd)) wordEnd--
                var wordStart = wordEnd
                while (wordStart > 0 && isWordChar(wordStart - 1)) wordStart--
                val word = if (wordEnd >= 0) title.substring(wordStart, wordEnd + 1).lowercase() else ""
                if (word in setOf("cd", "disc", "disk")) continue
                result.add(word to title.substring(start, end).toInt())
            }
            return result
        }

        // Deriving the album from its tracks

        /** The most common album tag, spelled as the majority spells it;
         *  without any tag, the folder's name. */
        internal fun title(tracks: List<Track>, folder: String): String =
            mostCommon(tracks.mapNotNull { it.album.trimmedOrNull() })
                ?: Paths.lastComponent(folder).takeIf { it.isNotEmpty() }
                ?: UNKNOWN_TITLE

        /**
         * Album artist tags win over track artists. Either way: a name
         * that two thirds of the tracks share (ignoring spelling noise)
         * is the album's artist, so one mistagged track does not turn an
         * album into a compilation; anything less does. A compilation
         * flag on most tracks settles it the other way when only track
         * artists are tagged: a tribute album with one artist on two
         * thirds of it is still a compilation. No name at all falls back
         * to the given one (the parent folder) or "Unknown Artist".
         */
        internal fun artist(tracks: List<Track>, fallback: String? = null): String {
            val albumArtists = tracks.mapNotNull { it.albumArtist.trimmedOrNull() }
            mostCommon(albumArtists)?.let { return if (agreed(it, albumArtists)) it else VARIOUS_ARTISTS }
            if (tracks.count { it.compilation } * 2 > tracks.size) return VARIOUS_ARTISTS
            val artists = tracks.mapNotNull { it.artist.trimmedOrNull() }
            mostCommon(artists)?.let { return if (agreed(it, artists)) it else VARIOUS_ARTISTS }
            return fallback ?: UNKNOWN_ARTIST
        }

        private fun agreed(common: String, names: List<String>): Boolean =
            names.count { looseKey(it) == looseKey(common) } * 3 >= names.size * 2

        /** The name as it sorts: a leading "The " goes to the back, so
         *  The Beatles file under B. Only "The": "A Tribe Called Quest"
         *  stays under A, as most people would look for it. */
        internal fun sortName(name: String): String {
            val trimmed = name.trim()
            return if (trimmed.length > 4 && trimmed.startsWith("the ", ignoreCase = true)) {
                trimmed.substring(4).trim() + ", The"
            } else {
                trimmed
            }
        }

        /** A folder name without its trailing number: "soul_1" and
         *  "soul_2" share the stem "soul". Empty when the name is only a
         *  number. */
        internal fun discStem(name: String): String = looseKey(name).trimEnd { it.isDigit() }

        /** Lowercased, letters and digits only: "Winter-Works" and
         *  "Winterworks" compare equal, as do "Band" and "Band;". */
        internal fun looseKey(text: String): String =
            text.lowercase().filter { it.isLetterOrDigit() }

        /** The parent folder, or the grandparent when the parent is a
         *  disc folder like "CD1", "Disc 2" or "Disk02". */
        internal fun groupingFolder(path: String): String {
            val folder = Paths.parent(path)
            val name = Paths.lastComponent(folder).lowercase()
            val isDiscFolder = listOf("cd", "disc", "disk").any { prefix ->
                name.length > prefix.length && name.startsWith(prefix) &&
                    name.substring(prefix.length).trim().all { it.isDigit() }
            }
            return if (isDiscFolder) Paths.parent(folder) else folder
        }

        /** Ties go to the value seen first, so the result is stable. */
        private fun mostCommon(values: List<String>): String? {
            if (values.isEmpty()) return null
            val counts = LinkedHashMap<String, Int>()
            for (value in values) counts[value] = (counts[value] ?: 0) + 1
            var best: Map.Entry<String, Int>? = null
            for (entry in counts) {
                if (best == null || entry.value > best.value) best = entry
            }
            return best?.key
        }

        private val TRACK_ORDER = Comparator<Track> { a, b ->
            val byDisc = (a.discNumber ?: 1).compareTo(b.discNumber ?: 1)
            if (byDisc != 0) return@Comparator byDisc
            val byTrack = (a.trackNumber ?: Int.MAX_VALUE).compareTo(b.trackNumber ?: Int.MAX_VALUE)
            if (byTrack != 0) return@Comparator byTrack
            naturalCompare(a.title, b.title)
        }
    }
}
