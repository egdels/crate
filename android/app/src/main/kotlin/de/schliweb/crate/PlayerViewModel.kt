// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import de.schliweb.crate.core.Album
import de.schliweb.crate.core.Favorites
import de.schliweb.crate.core.Folder
import de.schliweb.crate.core.Library
import de.schliweb.crate.core.Paths
import de.schliweb.crate.core.ScannedFile
import de.schliweb.crate.core.SearchIndex
import de.schliweb.crate.core.SearchQuery
import de.schliweb.crate.core.Track
import de.schliweb.crate.core.countText
import de.schliweb.crate.library.SafFileSource
import de.schliweb.crate.playback.ArtworkBitmapLoader
import de.schliweb.crate.playback.PlaybackService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The one piece of UI state: a snapshot of the library for the views,
 * and what the player is doing. Everything the views call goes through
 * here; the views never touch [Library] or the `MediaController`.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val app: CrateApplication get() = getApplication()
    private val library: Library get() = app.library

    var folders by mutableStateOf<List<Folder>>(emptyList())
        private set
    var tracks by mutableStateOf<List<Track>>(emptyList())
        private set
    var albums by mutableStateOf<List<Album>>(emptyList())
        private set
    var isScanning by mutableStateOf(false)
        private set
    /** False until the saved library has been read; until then the UI
     *  must not offer the folder picker as if the library were empty. */
    var isLoaded by mutableStateOf(false)
        private set
    /** Files read so far and files to read, while a scan is in its
     *  reading phase; null while it lists the folder. */
    var scanProgress by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    var lastScan by mutableStateOf<Library.RescanSummary?>(null)
        private set
    var lastError by mutableStateOf<String?>(null)
    /** A one-line result of something the user just did, shown in the
     *  footer until the next scan; null most of the time. */
    var notice by mutableStateOf<String?>(null)
        private set
    var selection by mutableStateOf<Selection?>(null)
    var searchText by mutableStateOf("")
    /** Albums for the grid, narrowed by the search field. */
    var filteredAlbums by mutableStateOf<List<Album>>(emptyList())
        private set
    /** Every track in album order, narrowed by the search field. */
    var filteredTracks by mutableStateOf<List<Track>>(emptyList())
        private set
    /** The tracks marked with a heart. Saved in a file of their own, so
     *  a tap on a heart never rewrites the library's index. */
    var favorites by mutableStateOf(Favorites())
        private set
    /** The favorites in album order, narrowed by the search field. */
    var favoriteTracks by mutableStateOf<List<Track>>(emptyList())
        private set
    private var searchIndex by mutableStateOf(SearchIndex(emptyList()))

    /** What the detail pane shows: one album, every track, or the
     *  favorites. */
    sealed interface Selection {
        data class AlbumId(val id: String) : Selection
        data object AllTracks : Selection
        data object Favorites : Selection
    }

    // Playback

    var current by mutableStateOf<Track?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    /** Seconds. */
    var currentTime by mutableDoubleStateOf(0.0)
        private set
    private var reportedDuration by mutableDoubleStateOf(0.0)
    var isShuffled by mutableStateOf(false)
        private set
    var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
        private set
    /** Cover of the album the current track belongs to, for the player bar. */
    var currentArtwork by mutableStateOf<Bitmap?>(null)
        private set

    val duration: Double
        get() = if (reportedDuration > 0) reportedDuration else current?.duration ?: 0.0

    private val controllerReady = CompletableDeferred<MediaController>()
    /** Tracks of the playlist last handed to the player, by media id. */
    private var queued: Map<String, Track> = emptyMap()
    private var artworkJob: Job? = null
    private val started = CompletableDeferred<Unit>()

    init {
        viewModelScope.launch {
            try {
                val t0 = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    library.load()
                    favorites = app.favorites.load() ?: Favorites()
                }
                val t1 = System.currentTimeMillis()
                refresh()
                Log.i(TAG, "load: ${tracks.size} tracks, read ${t1 - t0} ms, grouped ${System.currentTimeMillis() - t1} ms")
            } catch (e: Exception) {
                lastError = "Could not load the library: ${e.message}"
            }
            isLoaded = true
            started.complete(Unit)
            // An index from an older version lacks tags this one reads:
            // re-read the files once, with the grid already up and the
            // footer showing the progress.
            if (library.needsRescan) rescan()
        }
        viewModelScope.launch { connect() }
        // Off the main thread and once per keystroke: the field shows
        // what was typed right away, the lists follow when they are ready.
        viewModelScope.launch {
            snapshotFlow { searchIndex to searchText }.collectLatest { (index, text) ->
                val result = withContext(Dispatchers.Default) { index.search(SearchQuery(text)) }
                filteredAlbums = result.albums
                filteredTracks = result.tracks
                favoriteTracks = favorites.filter(result.tracks)
            }
        }
    }

    // Favorites

    fun isFavorite(track: Track): Boolean = track in favorites

    /** Whether every track of the album has a heart. */
    fun isFavorite(album: Album): Boolean = favorites.containsAll(album.tracks)

    fun toggleFavorite(track: Track) = favoritesChanged(favorites.toggle(track))

    /** Hearts the whole album, or takes the hearts off all of it. */
    fun setFavorite(on: Boolean, album: Album) = favoritesChanged(favorites.set(on, album.tracks))

    /** One thread, so two quick taps are written in order. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val favoritesWriter = Dispatchers.IO.limitedParallelism(1)

    private fun favoritesChanged(changed: Favorites) {
        favorites = changed
        favoriteTracks = changed.filter(filteredTracks)
        viewModelScope.launch(favoritesWriter) {
            try {
                app.favorites.save(changed)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { lastError = "Could not save the favorites: ${e.message}" }
            }
        }
    }

    // Derived state

    val selectedAlbum: Album?
        get() = (selection as? Selection.AlbumId)?.let { s -> albums.firstOrNull { it.id == s.id } }

    /** What the detail pane shows. Differs from [selection] while a
     *  search hides the selected album from the grid: then the search
     *  results take the detail, and the album returns with its tile. */
    val detail: Selection?
        get() {
            val album = selection as? Selection.AlbumId ?: return selection
            if (searchText.isNotBlank() && filteredAlbums.none { it.id == album.id }) return Selection.AllTracks
            return selection
        }

    /** One line for the footer: what the last scan did, otherwise the
     *  size of the library. */
    val statusText: String
        get() {
            notice?.let { return it }
            if (isScanning) {
                val progress = scanProgress
                return if (progress != null && progress.second > 0) {
                    "Scanning… ${progress.first} of ${progress.second} files"
                } else {
                    "Scanning…"
                }
            }
            val scan = lastScan ?: return librarySize
            val parts = mutableListOf<String>()
            if (scan.added > 0) parts.add("${scan.added} added")
            if (scan.updated > 0) parts.add("${scan.updated} updated")
            if (scan.removed > 0) parts.add("${scan.removed} removed")
            if (scan.failed > 0) parts.add("${scan.failed} unreadable")
            val change = if (parts.isEmpty()) "Nothing changed" else parts.joinToString(", ")
            return "$change · $librarySize"
        }

    private val librarySize: String
        get() = "${countText(tracks.size, "track")} in ${countText(folders.size, "folder")}"

    // Library

    /** The tree the system picker returned: keep the grant, add, scan. */
    fun addFolder(tree: Uri) {
        viewModelScope.launch {
            started.await()
            runCatching {
                app.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val folder = withContext(Dispatchers.IO) { SafFileSource.folder(app, tree) }
            scanning { library.addFolder(folder, progress) }
        }
    }

    /** Forgets the folder and its tracks. Nothing on disk is touched; the
     *  folder can be added again any time. Queued tracks from it go too,
     *  and if the one playing was among them, playback stops. */
    fun removeFolder(folder: Folder) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { library.removeFolder(folder) }
            } catch (e: Exception) {
                lastError = "Could not remove the folder: ${e.message}"
                return@launch
            }
            runCatching {
                app.contentResolver.releasePersistableUriPermission(Uri.parse(folder.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            lastScan = null
            notice = "Removed ${folder.name}"
            favoritesChanged(favorites.removeAllUnder(folder.path))
            val controller = controllerReady.takeIf { it.isCompleted }?.await()
            if (controller != null) {
                val keep = (0 until controller.mediaItemCount).filter { !Paths.isUnder(controller.getMediaItemAt(it).mediaId, folder.path) }
                if (keep.size != controller.mediaItemCount) {
                    if (current?.let { Paths.isUnder(it.path, folder.path) } == true) {
                        controller.stop()
                        controller.clearMediaItems()
                    } else {
                        for (index in (controller.mediaItemCount - 1) downTo 0) {
                            if (index !in keep) controller.removeMediaItem(index)
                        }
                    }
                }
            }
            refresh()
        }
    }

    fun rescan() {
        viewModelScope.launch {
            started.await()
            // A rescan is the moment to look again at albums that had no
            // cover last time.
            withContext(Dispatchers.IO) { app.artwork.forgetMissing() }
            scanning { library.rescan(progress) }
        }
    }

    private val progress = Library.Progress { read, total ->
        viewModelScope.launch { scanProgress = read to total }
    }

    private suspend fun scanning(work: suspend () -> Library.RescanSummary) {
        if (isScanning) return
        isScanning = true
        scanProgress = null
        notice = null
        val started = System.currentTimeMillis()
        try {
            lastScan = withContext(Dispatchers.IO) { work() }
            lastError = null
            Log.i(TAG, "scan: ${lastScan} in ${System.currentTimeMillis() - started} ms")
        } catch (e: Exception) {
            lastError = "Scan failed: ${e.message}"
        } finally {
            isScanning = false
            scanProgress = null
        }
        refresh()
    }

    private suspend fun refresh() {
        val (grouped, index) = withContext(Dispatchers.Default) { library.albums.let { it to SearchIndex(it) } }
        // All three at once, after the grouping: setting folders first
        // showed an empty grid for as long as the grouping took.
        folders = library.folders
        tracks = library.tracks
        albums = grouped
        searchIndex = index
        val selected = selection
        if (selected is Selection.AlbumId && grouped.none { it.id == selected.id }) selection = null
    }

    /** Cached per album; the first call for an album reads its files. */
    suspend fun artwork(album: Album): Bitmap? = app.artwork.thumbnail(album)

    // Opening files

    /** What a file manager hands over. A file that is in the library
     *  plays with its album queued from that track; a file from
     *  elsewhere plays alone, without touching the library. */
    fun open(uri: Uri) {
        viewModelScope.launch {
            started.await()
            val known = tracks.firstOrNull { it.uri == uri.toString() }
            if (known != null) {
                val album = albums.firstOrNull { album -> album.tracks.any { it.id == known.id } }
                if (album != null) {
                    play(album, album.tracks.indexOfFirst { it.id == known.id })
                    return@launch
                }
            }
            val track = try {
                withContext(Dispatchers.IO) { library.probe(describe(uri)) }
            } catch (e: Exception) {
                lastError = "Cannot open ${uri.lastPathSegment ?: uri}"
                return@launch
            }
            play(listOf(track))
        }
    }

    private fun describe(uri: Uri): ScannedFile {
        if (uri.scheme == "file") {
            val file = File(uri.path ?: "")
            return ScannedFile(uri.toString(), file.path, file.length(), file.lastModified())
        }
        var name = uri.lastPathSegment ?: "track"
        var size = 0L
        runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) {
                    it.getString(0)?.let { n -> name = n }
                    if (!it.isNull(1)) size = it.getLong(1)
                }
            }
        }
        return ScannedFile(uri.toString(), name, size, 0)
    }

    // Playback

    private suspend fun connect() {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val controller = MediaController.Builder(app, token).buildAsync().await()
        controller.addListener(listener)
        controllerReady.complete(controller)
        syncFromPlayer(controller)
        while (currentCoroutineContext().isActive) {
            if (controller.isPlaying) currentTime = controller.currentPosition / 1000.0
            delay(250)
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            controllerReady.takeIf { it.isCompleted }?.let { viewModelScope.launch { syncFromPlayer(it.await()) } }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            controllerReady.takeIf { it.isCompleted }?.let { viewModelScope.launch { syncFromPlayer(it.await()) } }
        }

        override fun onShuffleModeEnabledChanged(enabled: Boolean) {
            isShuffled = enabled
        }

        override fun onRepeatModeChanged(mode: Int) {
            repeatMode = mode
        }

        override fun onPlayerError(error: PlaybackException) {
            lastError = "Cannot play ${current?.title ?: "this file"}: ${error.errorCodeName}"
        }
    }

    private fun syncFromPlayer(controller: MediaController) {
        isPlaying = controller.isPlaying
        isShuffled = controller.shuffleModeEnabled
        repeatMode = controller.repeatMode
        val id = controller.currentMediaItem?.mediaId
        val track = id?.let { queued[it] ?: library.track(it) }
        val reported = controller.duration
        reportedDuration = if (reported == C.TIME_UNSET || reported <= 0) 0.0 else reported / 1000.0
        currentTime = controller.currentPosition.coerceAtLeast(0) / 1000.0
        if (track?.id != current?.id) {
            current = track
            loadArtwork(track)
        }
        if (track != null) lastError = null
    }

    private fun loadArtwork(track: Track?) {
        artworkJob?.cancel()
        val album = track?.let { t -> albums.firstOrNull { album -> album.tracks.any { it.id == t.id } } }
        if (album == null) {
            currentArtwork = null
            return
        }
        artworkJob = viewModelScope.launch {
            val image = app.artwork.thumbnail(album)
            if (current?.id == track.id) currentArtwork = image
        }
    }

    private fun withController(block: (MediaController) -> Unit) {
        viewModelScope.launch { block(controllerReady.await()) }
    }

    /** Queues the album and starts at the given track. */
    fun play(album: Album, index: Int = 0) {
        selection = Selection.AlbumId(album.id)
        play(album.tracks, index, asAlbum = true)
    }

    /** Queues the given tracks and starts at the given one. [asAlbum]
     *  picks the album gain over the track gain, as ReplayGain intends
     *  for listening to a record as a whole. */
    fun play(tracks: List<Track>, index: Int = 0, asAlbum: Boolean = false) {
        if (tracks.isEmpty()) return
        val byPath = HashMap<String, Album>()
        for (album in albums) for (track in album.tracks) byPath[track.id] = album
        val items = tracks.map { track -> mediaItem(track, byPath[track.id], gain(track, asAlbum)) }
        queued = tracks.associateBy { it.id }
        withController { controller ->
            controller.setMediaItems(items, index.coerceIn(0, items.size - 1), 0L)
            controller.prepare()
            controller.play()
        }
    }

    /** The loudness correction to play the track with, in dB. */
    private fun gain(track: Track, asAlbum: Boolean): Double =
        (if (asAlbum) track.albumGain ?: track.trackGain else track.trackGain ?: track.albumGain) ?: 0.0

    private fun mediaItem(track: Track, album: Album?, gain: Double): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album ?: album?.title)
            .setAlbumArtist(track.albumArtist ?: album?.artist)
            .setTrackNumber(track.trackNumber)
            .setTotalTrackCount(track.trackTotal)
            .setDiscNumber(track.discNumber)
            .setReleaseYear(track.year)
            .setGenre(track.genre)
            .setArtworkUri(album?.let { ArtworkBitmapLoader.uri(app.artwork.key(it)) })
            .setExtras(Bundle().apply { putDouble(PlaybackService.KEY_GAIN, gain) })
            .build()
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(Uri.parse(track.uri))
            .setMediaMetadata(metadata)
            .build()
    }

    fun togglePlayPause() = withController { controller ->
        if (controller.currentMediaItem == null) return@withController
        if (controller.isPlaying) {
            controller.pause()
        } else {
            if (controller.playbackState == Player.STATE_ENDED) controller.seekTo(0, 0L)
            if (controller.playbackState == Player.STATE_IDLE) controller.prepare()
            controller.play()
        }
    }

    fun next() = withController { it.seekToNext() }

    /** Like every player: early in a track, go back a track; later,
     *  restart the one playing. The player draws that line at 3 s. */
    fun previous() = withController { it.seekToPrevious() }

    fun seek(seconds: Double) {
        currentTime = seconds
        withController { it.seekTo((seconds * 1000).toLong()) }
    }

    fun setShuffle(on: Boolean) {
        isShuffled = on
        withController { it.shuffleModeEnabled = on }
    }

    /** Off, all, one, off. */
    fun cycleRepeat() {
        val next = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        repeatMode = next
        withController { it.repeatMode = next }
    }

    private companion object {
        const val TAG = "Crate"
    }

    override fun onCleared() {
        controllerReady.takeIf { it.isCompleted }?.let { deferred ->
            @Suppress("OPT_IN_USAGE")
            deferred.getCompleted().let { controller ->
                controller.removeListener(listener)
                controller.release()
            }
        }
    }
}
