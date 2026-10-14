// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Loudness normalisation as the files describe it: ReplayGain tags
 * (track and album gain in dB plus the peak), and iTunes Sound Check
 * (`iTunNORM`), which iTunes-ripped files carry instead. The values a
 * [Track] stores are already limited by the peak, so applying them can
 * never clip; the player only converts dB to a factor.
 */
object ReplayGain {
    /** More than any sane tag asks for. */
    const val MIN_DB = -24.0
    const val MAX_DB = 24.0

    /** "-6.50 dB", "+3.25 dB", "-6.5" → the number. */
    fun decibels(text: String): Double? {
        val trimmed = text.trim().lowercase()
        val number = if (trimmed.endsWith("db")) trimmed.dropLast(2).trim() else trimmed
        return number.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    /** "0.988" → 0.988; a peak of 0 means unknown. */
    fun peak(text: String): Double? =
        text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }

    /** The gain, capped so that the peak sample stays below full scale
     *  when the peak is known, and kept inside the range either way. */
    fun limited(gain: Double, peak: Double?): Double {
        var limited = gain
        if (peak != null && peak > 0) limited = min(limited, -20 * log10(peak))
        return min(max(limited, MIN_DB), MAX_DB)
    }

    /** iTunes Sound Check: ten hex fields. The first two are the volume
     *  adjustment per channel in thousandths relative to the reference
     *  level (gain in dB = -10·log10(value / 1000)), fields seven and
     *  eight the peak per channel out of 32768. The louder channel
     *  decides both. */
    fun soundCheck(text: String): Pair<Double, Double?>? {
        val fields = text.trim().split(Regex("\\s+")).mapNotNull { it.toLongOrNull(16) }
        if (fields.size < 2) return null
        val value = max(fields[0], fields[1])
        if (value <= 0) return null
        val gain = -10 * log10(value / 1000.0)
        val peak = if (fields.size >= 8) max(fields[6], fields[7]).takeIf { it > 0 }?.let { it / 32768.0 } else null
        return gain to peak
    }

    /** Limited gains from what a file offers: ReplayGain first, Sound
     *  Check as the track gain when there is no ReplayGain at all. */
    fun gains(
        track: Double?, trackPeak: Double?, album: Double?, albumPeak: Double?, soundCheck: Pair<Double, Double?>?,
    ): Pair<Double?, Double?> {
        var trackGain = track?.let { limited(it, trackPeak) }
        val albumGain = album?.let { limited(it, albumPeak) }
        if (trackGain == null && albumGain == null && soundCheck != null) {
            trackGain = limited(soundCheck.first, soundCheck.second)
        }
        return trackGain to albumGain
    }

    /** dB to the factor a gain stage wants. */
    fun factor(decibels: Double): Float = 10.0.pow(decibels / 20).toFloat()
}
