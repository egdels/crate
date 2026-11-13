// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.linux

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import de.schliweb.crate.core.Album
import de.schliweb.crate.core.FolderImages
import de.schliweb.crate.core.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Album covers, the rule the other apps use: a picture in the first
 * three tracks' tags, then the images in the album's folder, preferred
 * names first. Scaled to thumbnails and kept in memory only; the
 * prototype has no disk cache.
 */
class Artwork(private val edge: Int = 320) {
    private val cache = object : LinkedHashMap<String, ImageBitmap?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap?>) = size > 400
    }

    fun cached(album: Album): ImageBitmap? = synchronized(cache) { cache[album.id] }

    suspend fun cover(album: Album): ImageBitmap? {
        synchronized(cache) { if (cache.containsKey(album.id)) return cache[album.id] }
        val image = withContext(Dispatchers.IO) { runCatching { load(album) }.getOrNull() }
        synchronized(cache) { cache[album.id] = image }
        return image
    }

    private fun load(album: Album): ImageBitmap? {
        for (track in album.tracks.take(3)) {
            LinuxMetadataReader.cover(File(track.path))?.let(::decode)?.let { return it }
        }
        val folder = File(Paths.parent(album.tracks.first().path))
        val images = folder.listFiles { file -> file.isFile && FolderImages.isImageName(file.name) }.orEmpty().toList()
        for (image in FolderImages.order(images, File::getName, File::length)) {
            decode(image.readBytes())?.let { return it }
        }
        return null
    }

    private fun decode(bytes: ByteArray): ImageBitmap? {
        val source = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        val scale = minOf(1.0, edge.toDouble() / maxOf(source.width, source.height))
        val width = maxOf(1, (source.width * scale).toInt())
        val height = maxOf(1, (source.height * scale).toInt())
        val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = scaled.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.drawImage(source, 0, 0, width, height, null)
        graphics.dispose()
        return scaled.toComposeImageBitmap()
    }
}
