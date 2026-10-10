// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

/**
 * Which image in an album folder is the cover, when the files carry
 * none: cover.jpg, folder.jpg or similar first (how rippers and the
 * Windows media players leave album art), then anything named like a
 * cover, then the largest image there, which is usually a scan of the
 * front and better than a grey tile. Case-insensitive: "Cover.JPG"
 * counts.
 */
object FolderImages {
    /** Lowercased file names tried first, in order. */
    val preferredNames = listOf(
        "cover.jpg", "cover.jpeg", "cover.png", "folder.jpg", "folder.jpeg", "folder.png",
        "front.jpg", "front.jpeg", "front.png", "albumart.jpg", "albumartsmall.jpg", "album.jpg",
    )

    val imageExtensions = setOf("jpg", "jpeg", "png", "webp")

    fun isImageName(name: String): Boolean = Paths.extension(name) in imageExtensions

    /** The folder's image names in the order they should be tried. */
    fun <T> order(images: List<T>, name: (T) -> String, size: (T) -> Long): List<T> {
        if (images.isEmpty()) return emptyList()
        val byLowercase = LinkedHashMap<String, T>()
        for (image in images) byLowercase.putIfAbsent(name(image).lowercase(), image)
        val ordered = mutableListOf<T>()
        for (preferred in preferredNames) byLowercase[preferred]?.let(ordered::add)
        ordered.addAll(images.filter { image ->
            val lower = name(image).lowercase()
            image !in ordered && ("cover" in lower || "front" in lower)
        })
        ordered.addAll(images.filter { it !in ordered }.sortedByDescending(size))
        return ordered
    }
}
