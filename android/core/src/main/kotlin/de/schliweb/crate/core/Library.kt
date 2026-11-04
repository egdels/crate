// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.IOException

/**
 * The music library: a set of watched folders and the tracks found in
 * them, kept in memory and saved through a [LibraryStore] after every
 * change. Guarded by a mutex, so the UI can read while a rescan runs.
 *
 * Rescans are incremental: a file whose size and modification time match
 * the stored track is not read again, files that disappeared are dropped,
 * and a file whose tags cannot be read keeps its previous entry (or stays
 * absent) and is only counted as failed.
 */
class Library(
    private val store: LibraryStore,
    private val reader: MetadataReader,
    private val source: FileSource,
    /** Files read at once during a rescan. Reading is latency-bound:
     *  every file is a descriptor from the document provider plus a few
     *  positional reads. Measured in the emulator with 900 files: 4 in
     *  flight took 3.6 s, 8 took 2.6 s, 16 took 2.2 s. */
    concurrency: Int = 16,
) {
    data class RescanSummary(
        val added: Int = 0,
        val updated: Int = 0,
        val removed: Int = 0,
        val unchanged: Int = 0,
        /** Files whose tags could not be read; [failed] is their count. */
        val failedFiles: List<String> = emptyList(),
        /** Names of watched folders that could not be listed (a card or
         *  stick unplugged, a grant revoked); their tracks were kept. */
        val unreachableFolders: List<String> = emptyList(),
    ) {
        val failed: Int get() = failedFiles.size

        operator fun plus(other: RescanSummary) = RescanSummary(
            added + other.added, updated + other.updated, removed + other.removed,
            unchanged + other.unchanged, failedFiles + other.failedFiles,
            unreachableFolders + other.unreachableFolders,
        )
    }

    /** The folder's listing failed: nothing was changed for it. */
    class FolderUnreachable(val folder: Folder, cause: IOException) : IOException(cause.message ?: folder.name, cause)

    /** Progress of the reading phase of a rescan: files read so far and
     *  files to read. */
    fun interface Progress {
        fun report(read: Int, total: Int)
    }

    private val concurrency = maxOf(1, concurrency)
    private val lock = Mutex()

    @Volatile
    var folders: List<Folder> = emptyList()
        private set

    /** Sorted by path. */
    @Volatile
    var tracks: List<Track> = emptyList()
        private set

    /** True after loading an index written by an older version whose
     *  tracks lack fields a newer one reads: the next full rescan reads
     *  every file, not only the changed ones, and clears this. */
    @Volatile
    var needsRescan = false
        private set

    /** Folders already re-read in full for [needsRescan] in this
     *  session, so a folder that was unreachable is the only one read
     *  again in full on the next rescan. */
    private val reread = HashSet<String>()

    /** Restores the last saved state. Nothing saved yet means an empty
     *  library, not an error. */
    suspend fun load() = lock.withLock {
        val snapshot = store.load() ?: return@withLock
        folders = snapshot.folders
        tracks = snapshot.tracks.sortedBy { it.path }
        needsRescan = snapshot.version < LibrarySnapshot.CURRENT_VERSION
        reread.clear()
    }

    val albums: List<Album> get() = Album.group(tracks, folders.map { it.path })

    /** By path, through an index built once per [tracks] value: the
     *  view model and the service look up every queue entry on each
     *  player event, and a 3,000-file queue made that quadratic. */
    fun track(path: String): Track? {
        val current = tracks
        val index = byPath?.takeIf { it.tracks === current }
            ?: PathIndex(current, current.associateBy { it.path }).also { byPath = it }
        return index.map[path]
    }

    private class PathIndex(val tracks: List<Track>, val map: Map<String, Track>)

    @Volatile
    private var byPath: PathIndex? = null

    /** Reads one file into a [Track] without adding it to the library,
     *  for playing something the user opened from outside the watched
     *  folders. */
    suspend fun probe(file: ScannedFile): Track = makeTrack(file, reader.read(file))

    /** Adds the folder and scans it. A folder already watched, or inside
     *  a watched one, is only rescanned; a folder that contains watched
     *  ones replaces them. Nested folders were walked twice, and removing
     *  the inner one dropped tracks (and their favorites, bookmarks,
     *  practice settings) the outer one still covered. As on the Mac. */
    suspend fun addFolder(folder: Folder, progress: Progress? = null): RescanSummary {
        lock.withLock {
            val covered = folders.any { it.uri == folder.uri || it.path == folder.path || Paths.isUnder(folder.path, it.path) }
            if (!covered) folders = folders.filter { !Paths.isUnder(it.path, folder.path) } + folder
        }
        return rescan(folder, progress)
    }

    /** Forgets the folder and every track under it. Returns how many. */
    suspend fun removeFolder(folder: Folder): Int = lock.withLock {
        folders = folders.filter { it.uri != folder.uri }
        val before = tracks.size
        tracks = tracks.filter { !Paths.isUnder(it.path, folder.path) }
        save()
        before - tracks.size
    }

    /** Rescans every watched folder. One that cannot be listed is
     *  counted in [RescanSummary.unreachableFolders] and keeps its
     *  tracks; the others are scanned all the same. */
    suspend fun rescan(progress: Progress? = null): RescanSummary {
        var total = RescanSummary()
        for (folder in folders) {
            total += try {
                rescan(folder, progress)
            } catch (e: FolderUnreachable) {
                RescanSummary(unreachableFolders = listOf(folder.name))
            }
        }
        if (needsRescan) lock.withLock {
            if (folders.all { it.uri in reread }) {
                needsRescan = false
                reread.clear()
                save()
            }
        }
        return total
    }

    /** Throws [FolderUnreachable] when the folder cannot be listed. */
    suspend fun rescan(folder: Folder, progress: Progress? = null): RescanSummary {
        val walk = try {
            source.walk(folder)
        } catch (e: IOException) {
            throw FolderUnreachable(folder, e)
        }
        val scanned = walk.files
        val fullRead = needsRescan && lock.withLock { folder.uri !in reread }
        val scannedPaths = scanned.mapTo(HashSet()) { it.path }
        val existing = LinkedHashMap<String, Track>()
        for (track in tracks) if (Paths.isUnder(track.path, folder.path)) existing[track.path] = track

        var unchanged = 0
        val toRead = mutableListOf<ScannedFile>()
        for (file in scanned) {
            val track = existing[file.path]
            if (!fullRead && track != null && isUnchanged(track, file)) unchanged++ else toRead.add(file)
        }

        var added = 0
        var updated = 0
        val failed = mutableListOf<String>()
        for ((file, result) in readAll(toRead, progress)) {
            result.onSuccess { meta ->
                val wasKnown = existing.containsKey(file.path)
                existing[file.path] = makeTrack(file, meta)
                if (wasKnown) updated++ else added++
            }.onFailure {
                failed.add(file.path)
            }
        }
        failed.sort()

        // Not seen is not deleted when the walk could not look there.
        val removedPaths = existing.keys.filter { path ->
            path !in scannedPaths && walk.unreadable.none { path == it || Paths.isUnder(path, it) }
        }
        for (path in removedPaths) existing.remove(path)

        lock.withLock {
            tracks = (tracks.filter { !Paths.isUnder(it.path, folder.path) } + existing.values).sortedBy { it.path }
            if (fullRead) reread.add(folder.uri)
            save()
        }
        return RescanSummary(added, updated, removedPaths.size, unchanged, failed)
    }

    // Internals

    private fun save() {
        store.save(LibrarySnapshot(version = LibrarySnapshot.CURRENT_VERSION, folders = folders, tracks = tracks))
    }

    private suspend fun readAll(
        files: List<ScannedFile>,
        progress: Progress?,
    ): List<Pair<ScannedFile, Result<TrackMetadata>>> = coroutineScope {
        progress?.report(0, files.size)
        val permits = Semaphore(concurrency)
        var done = 0
        val counter = Mutex()
        files.map { file ->
            async {
                permits.withPermit {
                    val result = runCatching { reader.read(file) }
                    counter.withLock { done++; progress?.report(done, files.size) }
                    file to result
                }
            }
        }.map { it.await() }
    }

    companion object {
        fun makeTrack(file: ScannedFile, meta: TrackMetadata): Track = Track(
            uri = file.uri,
            path = file.path,
            fileSize = file.fileSize,
            modified = file.modified,
            title = meta.title.trimmedOrNull() ?: Paths.withoutExtension(Paths.lastComponent(file.path)),
            artist = meta.artist.trimmedOrNull(),
            albumArtist = meta.albumArtist.trimmedOrNull(),
            album = meta.album.trimmedOrNull(),
            trackNumber = meta.trackNumber,
            trackTotal = meta.trackTotal,
            discNumber = meta.discNumber,
            discTotal = meta.discTotal,
            year = meta.year,
            genre = meta.genre.trimmedOrNull(),
            composer = meta.composer.trimmedOrNull(),
            compilation = meta.compilation,
            artistSort = meta.artistSort.trimmedOrNull(),
            albumArtistSort = meta.albumArtistSort.trimmedOrNull(),
            albumSort = meta.albumSort.trimmedOrNull(),
            trackGain = meta.trackGain,
            albumGain = meta.albumGain,
            duration = meta.duration,
        )

        internal fun isUnchanged(track: Track, file: ScannedFile): Boolean =
            track.fileSize == file.fileSize && track.modified == file.modified
    }
}
