// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.linux

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.schliweb.crate.core.Album
import de.schliweb.crate.core.Library
import de.schliweb.crate.core.LocalFileSource
import de.schliweb.crate.core.Track
import de.schliweb.crate.core.countText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The only UI state, as on the other platforms: views read it and call
 *  its methods, never the library or the player. */
class PlayerModel(private val library: Library) : GstPlayer.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val player = GstPlayer(this)
    val artwork = Artwork()

    var albums by mutableStateOf<List<Album>>(emptyList())
        private set
    var selected by mutableStateOf<Album?>(null)
    var status by mutableStateOf("")
        private set
    var lastError by mutableStateOf<String?>(null)
    var scanning by mutableStateOf(false)
        private set

    var queue by mutableStateOf<List<Track>>(emptyList())
        private set
    var current by mutableStateOf<Track?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var position by mutableStateOf(0.0)
        private set
    var volume by mutableStateOf(1.0)
        private set

    private var scan: Job? = null

    val hasFolders: Boolean get() = library.folders.isNotEmpty()

    /** Loads the saved index, adds [folders] given on the command line
     *  and rescans when the index is older than this build. */
    fun start(folders: List<File>) {
        scope.launch {
            withContext(Dispatchers.IO) { library.load() }
            refresh()
            val added = folders.filter { dir -> library.folders.none { it.path == dir.path } }
            if (added.isNotEmpty()) added.forEach { addFolder(it) }
            else if (library.needsRescan) rescan()
        }
        scope.launch {
            while (isActive) {
                if (isPlaying) position = player.position()
                delay(250)
            }
        }
    }

    fun addFolder(directory: File) = runScan { library.addFolder(LocalFileSource.folder(directory), it) }

    /** Asks for a folder off the UI thread (the dialog is another
     *  process) and adds the answer. */
    fun pickFolder(pick: () -> File?) {
        scope.launch { withContext(Dispatchers.IO) { pick() }?.let(::addFolder) }
    }

    fun rescan() = runScan { library.rescan(it) }

    private fun runScan(work: suspend (Library.Progress) -> Library.RescanSummary) {
        if (scan?.isActive == true) return
        scan = scope.launch {
            scanning = true
            status = "Reading folders…"
            try {
                val summary = withContext(Dispatchers.IO) {
                    work { read, total -> scope.launch { status = "Reading tags… $read of $total" } }
                }
                refresh()
                if (summary.failed > 0) lastError = "${countText(summary.failed, "file")} could not be read."
            } catch (e: Exception) {
                lastError = "Scan failed: ${e.message}"
                refresh()
            } finally {
                scanning = false
            }
        }
    }

    private fun refresh() {
        albums = library.albums
        selected = selected?.let { old -> albums.firstOrNull { it.id == old.id } } ?: albums.firstOrNull()
        status = "${countText(library.tracks.size, "track")} in ${countText(library.folders.size, "folder")}"
    }

    // Playback

    fun play(album: Album, from: Track = album.tracks.first()) {
        val start = album.tracks.indexOf(from).coerceAtLeast(0)
        queue = album.tracks
        player.play(album.tracks, start, asAlbum = true)
        isPlaying = true
    }

    fun togglePlay() {
        when {
            current == null -> selected?.let { play(it) }
            isPlaying -> { player.pause(); isPlaying = false }
            else -> { player.resume(); isPlaying = true }
        }
    }

    fun next() = player.next()

    fun previous() = player.previous()

    fun seek(seconds: Double) {
        player.seek(seconds)
        position = seconds
    }

    fun changeVolume(value: Double) {
        volume = value
        // Cubic, so the slider's middle sounds like the middle.
        player.setVolume(value * value * value)
    }

    fun dispose() = player.dispose()

    // GstPlayer.Listener, called on GStreamer's threads.

    override fun started(index: Int) {
        scope.launch {
            current = queue.getOrNull(index)
            position = 0.0
        }
    }

    override fun finished() {
        scope.launch {
            isPlaying = false
            position = 0.0
        }
    }

    override fun failed(message: String) {
        scope.launch {
            lastError = message
            isPlaying = false
        }
    }
}
