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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
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
import de.schliweb.crate.core.PlaybackState
import de.schliweb.crate.playback.PlaybackService
import de.schliweb.crate.playback.mediaItems
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
    /** The tracks marked with a heart: a mirror of the process-wide
     *  [SharedFavorites], which the notification's heart changes too. */
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
    /** What plays after the current track, in playback order (the
     *  shuffle order while shuffled). The playlist index identifies the
     *  entry; the same track can be queued twice. */
    var upcoming by mutableStateOf<List<QueueEntry>>(emptyList())
        private set

    data class QueueEntry(val index: Int, val track: Track)

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
                withContext(Dispatchers.IO) { library.load() }
                app.favorites.load()
                val t1 = System.currentTimeMillis()
                refresh()
                Log.i(TAG, "load: ${tracks.size} tracks, read ${t1 - t0} ms, grouped ${System.currentTimeMillis() - t1} ms")
            } catch (e: Exception) {
                lastError = "Could not load the library: ${e.message}"
            }
            isLoaded = true
            started.complete(Unit)
            restorePlayback()
            // An index from an older version lacks tags this one reads:
            // re-read the files once, with the grid already up and the
            // footer showing the progress.
            if (library.needsRescan) rescan()
        }
        viewModelScope.launch { connect() }
        viewModelScope.launch {
            app.favorites.state.collect {
                favorites = it
                favoriteTracks = it.filter(filteredTracks)
            }
        }
        viewModelScope.launch { app.favorites.errors.collect { lastError = it } }
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

    fun toggleFavorite(track: Track) {
        app.favorites.update { it.toggle(track) }
    }

    /** Hearts the whole album, or takes the hearts off all of it. */
    fun setFavorite(on: Boolean, album: Album) {
        app.favorites.update { it.set(on, album.tracks) }
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
            app.favorites.update { it.removeAllUnder(folder.path) }
            val controller = controllerReady.takeIf { it.isCompleted }?.await()
            if (controller != null) {
                val keep = (0 until controller.mediaItemCount).filter { !Paths.isUnder(controller.getMediaItemAt(it).mediaId, folder.path) }
                if (keep.size != controller.mediaItemCount) {
                    if (current?.let { Paths.isUnder(it.path, folder.path) } == true) {
                        controller.stop()
                        controller.clearMediaItems()
                        viewModelScope.launch(playbackWriter) { app.playback.clear() }
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
        var ticks = 0
        while (currentCoroutineContext().isActive) {
            if (controller.isPlaying) {
                currentTime = controller.currentPosition / 1000.0
                if (++ticks % 40 == 0) savePlayback(controller)
            }
            delay(250)
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
            if (!playing) controllerReady.takeIf { it.isCompleted }?.let { viewModelScope.launch { savePlayback(it.await()) } }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            controllerReady.takeIf { it.isCompleted }?.let { viewModelScope.launch { syncFromPlayer(it.await()) } }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            controllerReady.takeIf { it.isCompleted }?.let { viewModelScope.launch { updateUpcoming(it.await()) } }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            controllerReady.takeIf { it.isCompleted }?.let { viewModelScope.launch { syncFromPlayer(it.await()) } }
        }

        override fun onShuffleModeEnabledChanged(enabled: Boolean) {
            isShuffled = enabled
            controllerReady.takeIf { it.isCompleted }?.let { viewModelScope.launch { updateUpcoming(it.await()) } }
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
        updateUpcoming(controller)
        savePlayback(controller)
    }

    /** Walks the timeline from the current item the way the player will,
     *  shuffle order included, once through to the end (repeat does not
     *  make the list longer). */
    private fun updateUpcoming(controller: MediaController) {
        val timeline = controller.currentTimeline
        if (timeline.isEmpty || controller.currentMediaItem == null) {
            upcoming = emptyList()
            return
        }
        val entries = mutableListOf<QueueEntry>()
        var index = controller.currentMediaItemIndex
        while (true) {
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, controller.shuffleModeEnabled)
            if (index == C.INDEX_UNSET) break
            val id = controller.getMediaItemAt(index).mediaId
            val track = queued[id] ?: library.track(id) ?: continue
            entries += QueueEntry(index, track)
        }
        upcoming = entries
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
        val items = mediaItems(app, tracks, albums, asAlbum)
        queued = tracks.associateBy { it.id }
        queueIsAlbum = asAlbum
        withController { controller ->
            controller.setMediaItems(items, index.coerceIn(0, items.size - 1), 0L)
            controller.prepare()
            controller.play()
        }
    }

    // Up Next

    /** Queues the tracks right after the one playing. With nothing
     *  playing, they start right away. */
    fun playNext(tracks: List<Track>) = enqueue(tracks, next = true)

    /** Queues the tracks at the end. With nothing playing, they start
     *  right away. */
    fun playLater(tracks: List<Track>) = enqueue(tracks, next = false)

    private fun enqueue(tracks: List<Track>, next: Boolean) {
        if (tracks.isEmpty()) return
        // Tracks from anywhere: each is levelled on its own.
        val items = mediaItems(app, tracks, albums, asAlbum = false)
        queued = queued + tracks.associateBy { it.id }
        withController { controller ->
            when {
                controller.currentMediaItem == null -> {
                    queueIsAlbum = false
                    controller.setMediaItems(items, 0, 0L)
                    controller.prepare()
                    controller.play()
                }
                next -> {
                    val start = controller.currentMediaItemIndex + 1
                    controller.addMediaItems(start, items)
                    // The playlist index means nothing to the shuffle
                    // order; the service moves the items behind the
                    // current one.
                    if (controller.shuffleModeEnabled) {
                        val args = Bundle().apply {
                            putInt(PlaybackService.KEY_START, start)
                            putInt(PlaybackService.KEY_COUNT, items.size)
                            putInt(PlaybackService.KEY_TOTAL, controller.mediaItemCount)
                        }
                        controller.sendCustomCommand(SessionCommand(PlaybackService.COMMAND_QUEUED_NEXT, Bundle.EMPTY), args)
                    }
                }
                else -> controller.addMediaItems(items)
            }
        }
    }

    /** Plays the queued entry now. Entries between the one playing and
     *  it are skipped, as with Next. */
    fun play(entry: QueueEntry) = withController { controller ->
        if (entry.index < controller.mediaItemCount) {
            controller.seekTo(entry.index, 0L)
            controller.play()
        }
    }

    /** Takes an upcoming entry out of the queue. The current entry is
     *  never in [upcoming], so playback is not affected. */
    fun remove(entry: QueueEntry) = withController { controller ->
        if (entry.index < controller.mediaItemCount && entry.index != controller.currentMediaItemIndex) {
            controller.removeMediaItem(entry.index)
        }
    }

    /** Empties the queue after the current track. */
    fun clearUpNext() = withController { controller ->
        // Highest index first, so the lower ones keep their meaning.
        for (index in upcoming.map { it.index }.sortedDescending()) {
            if (index < controller.mediaItemCount && index != controller.currentMediaItemIndex) controller.removeMediaItem(index)
        }
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

    // Resume

    /** Whether the playlist was started as an album, for the gains on
     *  restore. */
    private var queueIsAlbum = false

    /** One thread, so the files are written in order. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val playbackWriter = Dispatchers.IO.limitedParallelism(1)

    /** Writes down the playlist, the cursor and the position, so the
     *  next launch comes up paused right here. Called on every item
     *  transition and state change, on pause, and every ten seconds of
     *  playback; the store rewrites the playlist only when it changed.
     *  An empty playlist is not written: the app starts with one, before
     *  the restore. */
    private fun savePlayback(controller: MediaController) {
        val count = controller.mediaItemCount
        val index = controller.currentMediaItemIndex
        if (count == 0 || index !in 0 until count) return
        val state = PlaybackState(
            trackIds = (0 until count).map { controller.getMediaItemAt(it).mediaId },
            asAlbum = queueIsAlbum,
            currentIndex = index,
            position = controller.currentPosition.coerceAtLeast(0) / 1000.0,
            isShuffled = controller.shuffleModeEnabled,
            repeatMode = controller.repeatMode,
        )
        viewModelScope.launch(playbackWriter) {
            runCatching { app.playback.save(state) }
        }
    }

    /** Rebuilds the playlist the last run left behind, at the saved
     *  track and position, paused. Tracks no longer in the library are
     *  skipped; if the current one is gone, nothing is restored. */
    private suspend fun restorePlayback() {
        val controller = controllerReady.await()
        if (controller.mediaItemCount > 0) return
        val state = withContext(Dispatchers.IO) { runCatching { app.playback.load() }.getOrNull() } ?: return
        val byId = tracks.associateBy { it.id }
        if (byId[state.trackIds[state.currentIndex]] == null) return
        val restored = ArrayList<Track>(state.trackIds.size)
        var index = 0
        for ((offset, id) in state.trackIds.withIndex()) {
            val track = byId[id] ?: continue
            if (offset == state.currentIndex) index = restored.size
            restored += track
        }
        queueIsAlbum = state.asAlbum
        val items = withContext(Dispatchers.Default) { mediaItems(app, restored, albums, state.asAlbum) }
        queued = restored.associateBy { it.id }
        controller.setMediaItems(items, index, (state.position * 1000).toLong())
        controller.repeatMode = state.repeatMode
        controller.shuffleModeEnabled = state.isShuffled
        controller.prepare()
        syncFromPlayer(controller)
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
