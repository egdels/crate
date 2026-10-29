// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

import kotlin.math.max
import kotlin.math.min

/**
 * Jumping back and forth in a track by some seconds. The player bar
 * offers it where it helps: audiobooks, radio plays, podcasts, and any
 * track long enough to look for a place in it (a live set, a DJ mix).
 * A four-minute song keeps previous and next alone. The practice sheet
 * offers it always, in shorter steps. The same rules as the Mac's `Skip`.
 */
object Skip {
    /** Back less than forward, like Apple's players: back is "what did
     *  she say?", forward is "skip this part". Seconds. */
    const val BACK = 15.0
    const val FORWARD = 30.0
    /** The practice sheet's step: a bar or two of music. */
    const val PRACTICE_STEP = 5.0
    /** From here on a track counts as long, whatever its genre. */
    const val LONG_TRACK = 20 * 60.0
    /** How far before the loop's end a jump forward inside it stops. */
    private const val LOOP_END_MARGIN = 1.0

    /** Folded genre parts that mean spoken word, in English and German. */
    private val SPOKEN_GENRES = listOf("audiobook", "audio book", "horbuch", "horspiel", "spoken", "speech", "podcast", "lesung")

    /** Whether the player bar offers the jumps for this track. */
    fun isOffered(track: Track): Boolean {
        if (track.path.substringAfterLast('.', "").lowercase() == "m4b" || track.duration >= LONG_TRACK) return true
        val genre = track.genre?.foldedForSearch() ?: return false
        return SPOKEN_GENRES.any { it in genre }
    }

    sealed interface Target {
        data class Position(val seconds: Double) : Target
        /** The jump went past the end: on to the next track, as if the
         *  track had finished. */
        data object NextTrack : Target
    }

    /** Where a jump by [seconds] (negative: back) from [time] lands.
     *  Never before the start; inside a playing loop never out of it,
     *  since leaving the passage is a slip while practising. */
    fun target(time: Double, seconds: Double, duration: Double, loop: PracticeLoop? = null): Target {
        val to = time + seconds
        if (loop != null && time in loop) {
            return Target.Position(min(max(to, loop.start), max(loop.start, loop.end - LOOP_END_MARGIN)))
        }
        if (duration > 0 && to >= duration) return Target.NextTrack
        return Target.Position(max(0.0, to))
    }
}
