// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Where playback stood when the app last wrote it down: the queue as
 * track ids in playlist order, the cursor, the position in that track,
 * and the modes. The next launch comes up paused right there.
 */
data class PlaybackState(
    val trackIds: List<String>,
    /** Whether the queue was started as an album, which picks the album
     *  gain over the track gain. */
    val asAlbum: Boolean,
    /** Index into [trackIds]. */
    val currentIndex: Int,
    /** Seconds into the current track. */
    val position: Double,
    val isShuffled: Boolean,
    /** `Player.REPEAT_MODE_OFF` and friends, kept as the player's int. */
    val repeatMode: Int,
)

interface PlaybackStateStore {
    /** null when nothing has been saved, or what was saved is unusable. */
    fun load(): PlaybackState?
    fun save(state: PlaybackState)
    fun clear()
}

/**
 * Two files in the app's private storage, because the two halves change
 * at very different rates: `queue.json` holds the track ids (every track
 * of the library, when All Tracks is playing) and is rewritten only when
 * they change; `position.json` holds the cursor, the position and the
 * modes, a few dozen bytes written every few seconds of playback.
 */
class JsonPlaybackStateStore(directory: File) : PlaybackStateStore {
    val queueFile = File(directory, "queue.json")
    val positionFile = File(directory, "position.json")

    @Serializable
    private data class QueueFile(val version: Int = CURRENT_VERSION, val trackIds: List<String>, val asAlbum: Boolean)

    @Serializable
    private data class PositionFile(
        val version: Int = CURRENT_VERSION, val currentIndex: Int, val position: Double,
        val isShuffled: Boolean, val repeatMode: Int,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The queue half as last written, to skip the write when nothing
     *  in it changed. */
    private var writtenQueue: QueueFile? = null

    /** For tests: how often `queue.json` was actually written. */
    var queueWrites = 0
        private set

    @Synchronized
    override fun load(): PlaybackState? {
        if (!queueFile.exists() || !positionFile.exists()) return null
        val queue = json.decodeFromString(QueueFile.serializer(), queueFile.readText())
        val position = json.decodeFromString(PositionFile.serializer(), positionFile.readText())
        if (position.currentIndex !in queue.trackIds.indices) return null
        writtenQueue = queue
        return PlaybackState(
            queue.trackIds, queue.asAlbum, position.currentIndex, position.position.coerceAtLeast(0.0),
            position.isShuffled, position.repeatMode,
        )
    }

    @Synchronized
    override fun save(state: PlaybackState) {
        queueFile.parentFile?.mkdirs()
        val queue = QueueFile(trackIds = state.trackIds, asAlbum = state.asAlbum)
        if (queue != writtenQueue) {
            replace(queueFile, json.encodeToString(QueueFile.serializer(), queue))
            writtenQueue = queue
            queueWrites++
        }
        val position = PositionFile(
            currentIndex = state.currentIndex, position = state.position,
            isShuffled = state.isShuffled, repeatMode = state.repeatMode,
        )
        replace(positionFile, json.encodeToString(PositionFile.serializer(), position))
    }

    @Synchronized
    override fun clear() {
        queueFile.delete()
        positionFile.delete()
        writtenQueue = null
    }

    /** Written to a sibling first and renamed into place, like the index. */
    private fun replace(file: File, text: String) {
        val temp = File(file.path + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) throw java.io.IOException("could not replace ${file.path}")
        }
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}
