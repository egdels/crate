// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate

import android.app.Application
import de.schliweb.crate.core.JsonFavoritesStore
import de.schliweb.crate.core.JsonHistoryStore
import de.schliweb.crate.core.JsonLoudnessStore
import de.schliweb.crate.core.LoudnessAnalyzer
import de.schliweb.crate.core.JsonLibraryStore
import de.schliweb.crate.core.JsonPlaybackStateStore
import de.schliweb.crate.core.Library
import de.schliweb.crate.core.Track
import de.schliweb.crate.library.AndroidArtworkReader
import de.schliweb.crate.library.AndroidMetadataReader
import de.schliweb.crate.library.ArtworkCache
import de.schliweb.crate.library.MediaCodecLoudness
import de.schliweb.crate.library.SafFileSource
import de.schliweb.crate.library.SafLyricsSource
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Process-wide objects shared by the activity's view model and the
 * playback service: the library (one JSON index under the app's files)
 * the cover cache (thumbnails under the app's cache directory, which
 * the system may clear; they are rebuilt on demand), the favorites and
 * the loudness analysis.
 */
class CrateApplication : Application() {
    val library: Library by lazy {
        Library(
            store = JsonLibraryStore(File(filesDir, "library.json")),
            reader = AndroidMetadataReader(this),
            source = SafFileSource(this),
            concurrency = 16, // see Library: measured, not guessed
        )
    }

    /** The hearts, in a small file of their own next to the index,
     *  shared with the service for the notification's heart. */
    val favorites: SharedFavorites by lazy { SharedFavorites(JsonFavoritesStore(File(filesDir, "favorites.json"))) }

    /** What was played, newest first, for the Recently Played tile. */
    val history: SharedHistory by lazy { SharedHistory(JsonHistoryStore(File(filesDir, "history.json"))) }

    /** The measured loudness of files without ReplayGain tags, in
     *  `loudness.json`; the media items read the gains. */
    val loudness: LoudnessAnalyzer by lazy {
        LoudnessAnalyzer(
            JsonLoudnessStore(File(filesDir, "loudness.json")),
            measure = { MediaCodecLoudness.measure(this, it) },
            // The meter is CPU work; half the cores, two to four.
            concurrency = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4),
        )
    }

    /** Files measured so far and files to measure while the analysis
     *  runs; null otherwise. The view model mirrors it into the footer. */
    val analysisProgress = MutableStateFlow<LoudnessAnalyzer.Progress?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var analysisJob: Job? = null

    /** Measures what is pending in [tracks], two at a time, unless a run
     *  is already on. It lives here and not in the activity's view model
     *  so that Back, which finishes the activity, does not stop it: it
     *  runs as long as the process does, which is while the app is open
     *  or playing. The view model cancels it before a scan or a folder
     *  removal and starts it again after. */
    fun startAnalysis(tracks: List<Track>) {
        if (analysisJob?.isActive == true) return
        analysisJob = scope.launch {
            val pending = withContext(Dispatchers.IO) { loudness.load(); loudness.pending(tracks).size }
            if (pending == 0) return@launch
            analysisProgress.value = LoudnessAnalyzer.Progress(0, pending)
            try {
                loudness.run(tracks) { progress, _ -> analysisProgress.value = progress }
            } finally {
                analysisProgress.value = null
            }
        }
    }

    fun cancelAnalysis() {
        analysisJob?.cancel()
        analysisJob = null
    }

    /** Where playback stood, for the next launch; two small files next
     *  to the index. */
    val playback: JsonPlaybackStateStore by lazy { JsonPlaybackStateStore(filesDir) }

    /** Lyrics beside a track or in its tags, looked up when it plays. */
    val lyrics: SafLyricsSource by lazy { SafLyricsSource(this) }

    val artwork: ArtworkCache by lazy {
        ArtworkCache(File(cacheDir, "artwork"), AndroidArtworkReader(this))
    }
}
