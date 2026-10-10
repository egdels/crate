// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

import java.text.Normalizer

/**
 * The few path operations the library needs, on plain "/"-separated
 * strings. Track paths are not file system paths on Android (they are
 * built from document names), so java.io.File would be the wrong tool.
 */
object Paths {
    /** Everything after the last "/", or the whole string without one. */
    fun lastComponent(path: String): String = path.substringAfterLast('/')

    /** Everything before the last "/"; empty when there is none, which
     *  marks the top. */
    fun parent(path: String): String = if ('/' in path) path.substringBeforeLast('/') else ""

    fun withoutExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot >= 0) name.substring(dot + 1).lowercase() else ""
    }

    fun isUnder(path: String, folder: String): Boolean =
        path.startsWith(if (folder.endsWith("/")) folder else "$folder/")

    fun join(folder: String, name: String): String =
        if (folder.isEmpty() || folder.endsWith("/")) folder + name else "$folder/$name"
}

/** Trimmed, or null when nothing is left. */
internal fun String?.trimmedOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Lowercased with diacritics stripped, so "Señorita" and "senorita"
 * compare equal. The stand-in for Foundation's localized standard
 * comparison.
 */
fun String.foldedForSearch(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .lowercase()

private val COMBINING_MARKS = Regex("\\p{M}+")

/**
 * Case-insensitive, diacritic-insensitive, and runs of digits compare by
 * value so that "Track 2" sorts before "Track 10". Equal under that rule
 * falls back to the plain comparison, so the order stays total.
 */
fun naturalCompare(a: String, b: String): Int {
    val x = a.foldedForSearch()
    val y = b.foldedForSearch()
    var i = 0
    var j = 0
    while (i < x.length && j < y.length) {
        val cx = x[i]
        val cy = y[j]
        if (cx.isDigit() && cy.isDigit()) {
            var ei = i
            while (ei < x.length && x[ei].isDigit()) ei++
            var ej = j
            while (ej < y.length && y[ej].isDigit()) ej++
            val nx = x.substring(i, ei).trimStart('0')
            val ny = y.substring(j, ej).trimStart('0')
            if (nx.length != ny.length) return nx.length.compareTo(ny.length)
            val byValue = nx.compareTo(ny)
            if (byValue != 0) return byValue
            i = ei
            j = ej
        } else {
            if (cx != cy) return cx.compareTo(cy)
            i++
            j++
        }
    }
    val byLength = (x.length - i).compareTo(y.length - j)
    return if (byLength != 0) byLength else a.compareTo(b)
}
