// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

/**
 * The words of a track, one line at a time, each with the moment it is
 * sung when the source is timed (an LRC file) and without when it is
 * plain text (a USLT frame, a ©lyr atom, a LYRICS comment).
 */
data class Lyrics(val lines: List<Line>) {
    /** [time] in seconds into the track, null for plain text. */
    data class Line(val time: Double?, val text: String)

    val isEmpty: Boolean get() = lines.isEmpty()
    val isTimed: Boolean get() = lines.any { it.time != null }

    /** The line being sung at [time]: the last timed one at or before
     *  it. null before the first, and for plain text. */
    fun indexAt(time: Double): Int? {
        var found: Int? = null
        for ((index, line) in lines.withIndex()) {
            val at = line.time ?: continue
            if (at <= time) found = index else break
        }
        return found
    }

    companion object {
        /** LRC when the text has time stamps ("[mm:ss.xx]", also the
         *  "[mm:ss:xx]" some files have), otherwise plain lines. LRC
         *  header tags are dropped, "[offset:ms]" is applied, a line with
         *  several stamps becomes several lines, and the result is in
         *  time order. */
        fun parse(text: String): Lyrics {
            val timed = mutableListOf<Line>()
            val plain = mutableListOf<Line>()
            var offset = 0.0
            var sawStamp = false
            for (raw in text.split("\r\n", "\n")) {
                val line = raw.trim()
                var rest = line
                val stamps = mutableListOf<Double>()
                while (true) {
                    val (tag, after) = leadingTag(rest) ?: break
                    rest = after
                    val seconds = seconds(tag)
                    when {
                        seconds != null -> stamps += seconds
                        tag.lowercase().startsWith("offset:") -> tag.substring(7).trim().toDoubleOrNull()?.let { offset = it / 1000 }
                    }
                }
                val words = rest.trim()
                if (stamps.isEmpty()) {
                    if (rest.length == line.length) plain += Line(null, words)
                    continue
                }
                sawStamp = true
                if (words.isNotEmpty()) for (stamp in stamps) timed += Line(stamp, words)
            }
            if (sawStamp) {
                return Lyrics(timed.sortedBy { it.time }.map { Line(maxOf(0.0, it.time!! + offset), it.text) })
            }
            val lines = mutableListOf<Line>()
            for (line in plain) {
                if (line.text.isEmpty() && (lines.lastOrNull()?.text?.isEmpty() != false)) continue
                lines += line
            }
            while (lines.lastOrNull()?.text?.isEmpty() == true) lines.removeAt(lines.size - 1)
            return Lyrics(lines)
        }

        /** "[tag]" at the start, and what follows it. */
        private fun leadingTag(s: String): Pair<String, String>? {
            if (!s.startsWith("[")) return null
            val close = s.indexOf(']')
            if (close < 0) return null
            val tag = s.substring(1, close)
            if (tag.isEmpty() || '[' in tag) return null
            return tag to s.substring(close + 1)
        }

        /** "mm:ss", "mm:ss.xx", "mm:ss:xx", "h:mm:ss.xx". */
        private fun seconds(stamp: String): Double? {
            val parts = stamp.split(':')
            if (parts.size !in 2..4) return null
            val numbers = parts.mapIndexed { index, part ->
                if (index < parts.size - 1 && '.' in part) return null
                part.toDoubleOrNull() ?: return null
            }
            return when (numbers.size) {
                2 -> numbers[0] * 60 + numbers[1]
                3 -> if (parts[2].length <= 3 && parts[1].length == 2 && '.' !in parts[2]) {
                    numbers[0] * 60 + numbers[1] + numbers[2] / Math.pow(10.0, parts[2].length.toDouble())
                } else {
                    numbers[0] * 3600 + numbers[1] * 60 + numbers[2]
                }
                else -> numbers[0] * 3600 + numbers[1] * 60 + numbers[2] + numbers[3] / Math.pow(10.0, parts[3].length.toDouble())
            }
        }
    }
}

interface LyricsSource {
    /** null when the track has none anywhere. */
    suspend fun lyrics(track: Track): Lyrics?
}
