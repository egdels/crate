// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

import java.util.Locale

/** "3:38" or "1:02:15". */
fun timeText(seconds: Double): String {
    val total = if (seconds.isFinite() && seconds > 0) seconds.toLong() else 0L
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, secs)
    }
}

/** "1 hr 12 min" for an album, "3 min" for a short one. */
fun lengthText(seconds: Double): String {
    val total = maxOf(if (seconds.isFinite()) seconds.toLong() else 0L, 60L)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    return if (hours > 0) "$hours hr $minutes min" else "$minutes min"
}

fun countText(count: Int, singular: String, plural: String = singular + "s"): String =
    if (count == 1) "1 $singular" else "${String.format(Locale.ROOT, "%,d", count)} $plural"
