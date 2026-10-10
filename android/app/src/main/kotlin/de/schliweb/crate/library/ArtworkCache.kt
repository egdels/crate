// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.util.LruCache
import de.schliweb.crate.core.Album
import de.schliweb.crate.core.FlacMetadata
import de.schliweb.crate.core.FolderImages
import de.schliweb.crate.core.Paths
import de.schliweb.crate.core.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds the cover image for one track: the picture embedded in its tags,
 * or failing that an image file lying next to it. Returns the encoded
 * bytes untouched; [ArtworkCache] decodes and scales. null means the
 * file has no cover, which is not an error.
 */
interface ArtworkReader {
    suspend fun artwork(track: Track): ByteArray?
}

class AndroidArtworkReader(private val context: Context) : ArtworkReader {
    override suspend fun artwork(track: Track): ByteArray? = withContext(Dispatchers.IO) {
        val uri = Uri.parse(track.uri)
        val embedded = if (Paths.extension(track.path) == "flac") {
            context.contentResolver.openInputStream(uri)?.let { FlacMetadata.read(it).cover?.data }
        } else {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.embeddedPicture
            } finally {
                runCatching { retriever.release() }
            }
        }
        embedded?.takeIf { it.isNotEmpty() } ?: folderImage(uri)
    }

    /**
     * An image in the track's folder. The folder is the document whose id
     * is the track's id up to the last "/", which holds for the storage
     * providers that build ids from paths (the device's own storage, SD
     * cards, USB drives). Providers with opaque ids get no folder images;
     * their embedded pictures still work.
     */
    private fun folderImage(uri: Uri): ByteArray? {
        if (!DocumentsContract.isTreeUri(uri)) return null
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        if ('/' !in documentId) return null
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, documentId.substringBeforeLast('/'))
        data class Image(val id: String, val name: String, val size: Long)
        val images = mutableListOf<Image>()
        runCatching {
            context.contentResolver.query(children, PROJECTION, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1) ?: continue
                    if (FolderImages.isImageName(name)) images.add(Image(cursor.getString(0), name, cursor.getLong(2)))
                }
            }
        }
        for (image in FolderImages.order(images, { it.name }, { it.size })) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(uri, image.id))?.use { it.readBytes() }
            }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) return bytes
        }
        return null
    }

    private companion object {
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
        )
    }
}

/**
 * Album thumbnails, scaled once and kept on disk so the grid never
 * decodes a 2 MB cover twice. Keyed by album, not file: the first track
 * that yields a picture decides the album's cover.
 *
 * Three layers: memory for what this process has decoded, JPEG files
 * under [directory] for what any run has decoded, and the
 * [ArtworkReader] for everything else. An album with no cover is
 * remembered too (empty marker file), otherwise every launch would
 * re-read all its files only to find nothing again. A read that fails
 * (share gone, permission lost) is not remembered: the next request for
 * that album tries again.
 */
class ArtworkCache(val directory: File, private val reader: ArtworkReader) {
    private sealed interface Lookup {
        class Found(val bitmap: Bitmap) : Lookup
        object None : Lookup
        object Failed : Lookup
    }

    private val memory = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val inFlight = HashMap<String, Deferred<Lookup>>()
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Albums whose key has been handed out, so the playback service can
     *  resolve an artwork URI back to the album. */
    private val albumsByKey = ConcurrentHashMap<String, Album>()

    /** The album's thumbnail, or null when none of its files carries one.
     *  Concurrent callers for the same album share one lookup. */
    suspend fun thumbnail(album: Album): Bitmap? {
        memory.get(album.id)?.let { return it }
        if (album.id in missing) return null
        val deferred = lock.withLock { inFlight.getOrPut(album.id) { scope.async { lookup(album) } } }
        val result = deferred.await()
        lock.withLock { inFlight.remove(album.id) }
        return when (result) {
            is Lookup.Found -> result.bitmap.also { memory.put(album.id, it) }
            Lookup.None -> null.also { missing.add(album.id) }
            Lookup.Failed -> null
        }
    }

    /** A short, file-name-safe key for the album, also usable as an
     *  artwork URI for the media session. */
    fun key(album: Album): String = fileName(album).also { albumsByKey[it] = album }

    suspend fun thumbnail(key: String): Bitmap? = albumsByKey[key]?.let { thumbnail(it) }

    /** Drops the "no cover" markers so a rescan can pick up newly tagged
     *  albums. Found covers stay. */
    fun forgetMissing() {
        missing.clear()
        directory.listFiles()?.filter { it.name.endsWith(MISSING_SUFFIX) }?.forEach { it.delete() }
    }

    private suspend fun lookup(album: Album): Lookup {
        val file = File(directory, fileName(album) + ".jpg")
        val marker = File(directory, fileName(album) + MISSING_SUFFIX)
        if (marker.exists()) return Lookup.None
        if (file.exists()) decode(file.readBytes())?.let { return Lookup.Found(it) }
        var failed = false
        for (track in album.tracks.take(TRACKS_TRIED)) {
            val data = try {
                reader.artwork(track)
            } catch (e: Exception) {
                failed = true
                continue
            } ?: continue
            val bitmap = decode(data) ?: continue
            directory.mkdirs()
            runCatching { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
            return Lookup.Found(bitmap)
        }
        if (failed) return Lookup.Failed
        directory.mkdirs()
        runCatching { marker.createNewFile() }
        return Lookup.None
    }

    companion object {
        /** Longest edge of a cached thumbnail, in pixels. */
        const val MAX_PIXELS = 600
        /** Files tried per album before giving up; a cover-less album with
         *  40 tracks must not cost 40 reads. */
        const val TRACKS_TRIED = 3
        private const val MISSING_SUFFIX = ".none"

        /** Album ids carry arbitrary artist and title text; the file name
         *  is a hash of it, so slashes, length and case never matter. */
        fun fileName(album: Album): String =
            MessageDigest.getInstance("SHA-256").digest(album.id.toByteArray()).joinToString("") { "%02x".format(it) }

        /** Decodes with subsampling so a large cover never materialises at
         *  full size. */
        fun decode(data: ByteArray): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PIXELS) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: return null
            val longest = maxOf(bitmap.width, bitmap.height)
            if (longest <= MAX_PIXELS) return bitmap
            val scale = MAX_PIXELS.toFloat() / longest
            return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        }
    }
}
