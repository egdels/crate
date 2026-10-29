// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import de.schliweb.crate.CrateApplication
import de.schliweb.crate.MainActivity
import de.schliweb.crate.SharedFavorites
import de.schliweb.crate.SharedPractice
import de.schliweb.crate.core.PracticeLoop
import de.schliweb.crate.core.ReplayGain
import de.schliweb.crate.core.Skip
import de.schliweb.crate.core.Track
import de.schliweb.crate.library.ArtworkCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Owns the player. A foreground media service, so the music keeps
 * playing when the activity goes away, the system shows the media
 * notification with transport controls, and headsets, Bluetooth and the
 * lock screen control it. The view model talks to it through a
 * `MediaController`.
 *
 * ExoPlayer's playlist is the play queue: it handles next, previous,
 * shuffle, repeat and gapless transitions itself (MP3 LAME headers,
 * iTunes gapless atoms, FLAC), with audio focus and "becoming noisy"
 * (headphones pulled) handled by the player.
 *
 * Loudness: each media item carries its gain in dB in the metadata
 * extras ([KEY_GAIN]); on every item transition the [GainProcessor] in
 * the audio sink takes it over. The sink buffers a little ahead, so the
 * switch lands a fraction of a second off the exact join; within an
 * album the gain is the album's and does not change.
 *
 * Shuffle order: the player draws one when the playlist is set and keeps
 * it, so turning shuffle on could leave the current track near the end
 * of the lap, and items added while shuffled land at random spots. Both
 * are fixed here, where the `ExoPlayer` is: shuffle on puts the current
 * track first, and the view model's [COMMAND_QUEUED_NEXT] puts the items
 * it just inserted right behind it. A controller cannot set the shuffle
 * order itself.
 *
 * The notification carries a heart for the current track, a custom
 * command button whose icon follows the process-wide [SharedFavorites];
 * tapping it toggles the heart there, which the app's own hearts mirror.
 *
 * Practice: the current item's tempo, key and tuning from the
 * process-wide [SharedPractice] become the player's `PlaybackParameters`
 * (Sonic in the audio sink: speed and pitch independent), on every item
 * transition and every change. The loop is a `PlayerMessage` at its
 * end, kept after delivery so that it fires on every pass, which seeks
 * back to its start. That seek flushes the renderers, so unlike the
 * Mac's scheduled segments the jump has a short gap.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val gain = GainProcessor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val favorites: SharedFavorites get() = (application as CrateApplication).favorites
    private val practice: SharedPractice get() = (application as CrateApplication).practice
    private var loopMessage: PlayerMessage? = null
    /** The item id and loop the message stands for. */
    private var loopTarget: Pair<String, PracticeLoop>? = null
    /** The item the settings were last applied to. */
    private var practisedId: String? = null

    override fun onCreate() {
        super.onCreate()
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(gain))
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
                    .build()
        }
        val player = ExoPlayer.Builder(this, renderers, DefaultMediaSourceFactory(localDataSources(this)))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            // The notification's jumps for audiobooks (`updateButtons`).
            .setSeekBackIncrementMs((Skip.BACK * 1000).toLong())
            .setSeekForwardIncrementMs((Skip.FORWARD * 1000).toLong())
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val decibels = mediaItem?.mediaMetadata?.extras?.getDouble(KEY_GAIN) ?: 0.0
                gain.factor = ReplayGain.factor(decibels)
                Log.i(TAG, "gain: ${"%.1f".format(decibels)} dB for ${mediaItem?.mediaMetadata?.title}")
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                // Items moved: the loop message points at an index.
                applyPractice(player, force = true)
            }

            override fun onShuffleModeEnabledChanged(enabled: Boolean) {
                if (enabled) reorder(player, queuedNext = IntRange.EMPTY, currentFirst = true)
            }
        })
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateButtons()
                recordPlay(player)
                applyPractice(player)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) recordPlay(player)
            }
        })
        scope.launch { (application as CrateApplication).history.load() }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setCallback(callback)
            .setSessionActivity(openApp)
            .setBitmapLoader(ArtworkBitmapLoader((application as CrateApplication).artwork))
            .setMediaButtonPreferences(listOf(heartButton(false)))
            .build()
        scope.launch {
            favorites.load()
            favorites.state.collect { updateButtons() }
        }
        scope.launch {
            practice.load()
            practice.state.collect { applyPractice(player) }
        }
    }

    /** Puts the current item's practice settings into effect. */
    private fun applyPractice(player: ExoPlayer, force: Boolean = false) {
        val id = player.currentMediaItem?.mediaId
        val settings = id?.let { practice.current[it] }
        val parameters = if (settings == null) PlaybackParameters.DEFAULT
            else PlaybackParameters(settings.tempo.toFloat(), settings.pitchFactor)
        if (player.playbackParameters != parameters) player.playbackParameters = parameters

        val duration = player.duration.takeIf { it != C.TIME_UNSET }?.let { it / 1000.0 } ?: 0.0
        val loop = settings?.activeLoop?.clampedTo(duration)
        val target = if (id != null && loop != null) id to loop else null
        val sameItem = id == practisedId
        practisedId = id
        if (target == loopTarget && !force) return
        // Only a loop just set or moved on the track playing jumps back,
        // like the Mac's setLoop. Not a re-armed one: the timeline also
        // changes when the next item is prepared for the gapless join,
        // and a seek past the loop's end must not be undone by that.
        val changedHere = sameItem && target != null && target != loopTarget
        loopMessage?.cancel()
        loopMessage = null
        loopTarget = target
        if (target == null) return
        val start = (loop!!.start * 1000).toLong()
        val end = (loop.end * 1000).toLong()
        if (changedHere && player.currentPosition >= end) player.seekTo(start)
        loopMessage = player.createMessage { _, _ -> if (loopTarget == target) player.seekTo(start) }
            .setLooper(mainLooper)
            .setPosition(player.currentMediaItemIndex, end)
            .setDeleteAfterDelivery(false)
            .send()
    }

    /** The id the history was last told about, so a track counts once
     *  per start, not once per pause and play. */
    private var recordedId: String? = null

    /** Recently Played: a track counts when it is playing (a restored,
     *  paused playlist does not count until Play). */
    private fun recordPlay(player: Player) {
        val id = player.currentMediaItem?.mediaId ?: return
        if (!player.playWhenReady || id == recordedId) return
        recordedId = id
        (application as CrateApplication).history.update { it.record(id) }
    }

    /** The notification's heart, filled when the current track has one,
     *  and for audiobooks and long tracks the jumps in place of previous
     *  and next, as Apple's players do. */
    private fun updateButtons() {
        val session = session ?: return
        val id = session.player.currentMediaItem?.mediaId
        val heart = heartButton(id != null && id in favorites.current.ids)
        val track = id?.let { (application as CrateApplication).library.track(it) }
        session.setMediaButtonPreferences(if (track != null && Skip.isOffered(track)) listOf(skipBack, skipForward, heart) else listOf(heart))
    }

    private val skipBack = CommandButton.Builder(CommandButton.ICON_SKIP_BACK_15)
        .setDisplayName("Back 15 seconds")
        .setPlayerCommand(Player.COMMAND_SEEK_BACK)
        .setSlots(CommandButton.SLOT_BACK)
        .build()

    private val skipForward = CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
        .setDisplayName("Forward 30 seconds")
        .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
        .setSlots(CommandButton.SLOT_FORWARD)
        .build()

    private fun heartButton(on: Boolean): CommandButton =
        CommandButton.Builder(if (on) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName(if (on) "Remove from favorites" else "Add to favorites")
            .setSessionCommand(SessionCommand(COMMAND_TOGGLE_FAVORITE, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    private val callback = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(COMMAND_QUEUED_NEXT, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_TOGGLE_FAVORITE, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands).build()
        }

        /** The system's "resume" after the process was gone (its media
         *  controls, a headset button): the playlist the last run wrote
         *  down, at the saved track and position. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
            val app = application as CrateApplication
            val state = withContext(Dispatchers.IO) {
                if (app.library.tracks.isEmpty()) app.library.load()
                app.playback.load()
            } ?: throw IllegalStateException("nothing to resume")
            val byId = app.library.tracks.associateBy { it.id }
            if (byId[state.trackIds[state.currentIndex]] == null) throw IllegalStateException("current track is gone")
            val restored = ArrayList<Track>(state.trackIds.size)
            var index = 0
            for ((offset, id) in state.trackIds.withIndex()) {
                val track = byId[id] ?: continue
                if (offset == state.currentIndex) index = restored.size
                restored += track
            }
            val items = withContext(Dispatchers.Default) { mediaItems(app, restored, app.library.albums, state.asAlbum) }
            MediaItemsWithStartPosition(items, index, (state.position * 1000).toLong())
        }

        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == COMMAND_TOGGLE_FAVORITE) {
                val id = session.player.currentMediaItem?.mediaId
                val track = id?.let { (application as CrateApplication).library.track(it) }
                if (track != null) favorites.update { it.toggle(track) }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction != COMMAND_QUEUED_NEXT) return super.onCustomCommand(session, controller, customCommand, args)
            val start = args.getInt(KEY_START, -1)
            val count = args.getInt(KEY_COUNT, 0)
            val total = args.getInt(KEY_TOTAL, -1)
            val player = session.player as? ExoPlayer
            if (player != null && start >= 0 && count > 0) {
                // The controller's addMediaItems reaches the player through
                // the callback's onAddMediaItems future, after this command:
                // wait until the playlist is as long as the controller saw it.
                if (player.mediaItemCount == total) {
                    reorder(player, queuedNext = start until start + count, currentFirst = false)
                } else {
                    player.addListener(object : Player.Listener {
                        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                            if (player.mediaItemCount != total) return
                            player.removeListener(this)
                            reorder(player, queuedNext = start until start + count, currentFirst = false)
                        }
                    })
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    /** Rewrites the shuffle order: with [currentFirst] the current item
     *  leads and everything else follows in fresh random order; otherwise
     *  the lap stays as it is and [queuedNext] moves right behind the
     *  current item. */
    private fun reorder(player: ExoPlayer, queuedNext: IntRange, currentFirst: Boolean) {
        val count = player.mediaItemCount
        if (count == 0 || !player.shuffleModeEnabled) return
        val current = player.currentMediaItemIndex
        val timeline = player.currentTimeline
        val order = mutableListOf<Int>()
        var index = timeline.getFirstWindowIndex(true)
        while (index != C.INDEX_UNSET) {
            order += index
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
        }
        val inserted = queuedNext.filter { it in 0 until count && it != current }
        val currentAt = order.indexOf(current).coerceAtLeast(0)
        val before = order.take(currentAt).filter { it !in inserted }
        val after = order.drop(currentAt + 1).filter { it != current && it !in inserted }
        val result = if (currentFirst) listOf(current) + (before + after).shuffled() else before + current + inserted + after
        player.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(result.toIntArray(), System.nanoTime()))
    }

    companion object {
        private const val TAG = "Crate"

        /** Double, dB: the item's loudness correction, see `Track.trackGain`. */
        const val KEY_GAIN = "de.schliweb.crate.gain"

        /** Custom session command from the view model: the items at
         *  [KEY_START] (int, count [KEY_COUNT]) were just inserted as
         *  "play next" into a playlist now [KEY_TOTAL] long; while
         *  shuffled, make them play next. */
        const val COMMAND_QUEUED_NEXT = "de.schliweb.crate.queued_next"
        const val KEY_START = "start"
        const val KEY_COUNT = "count"
        const val KEY_TOTAL = "total"

        /** The notification's heart: toggles the current track's favorite. */
        const val COMMAND_TOGGLE_FAVORITE = "de.schliweb.crate.toggle_favorite"
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}

/**
 * Resolves the `crate://album/<key>` artwork URIs the view model puts on
 * its media items through the shared [ArtworkCache], so the notification
 * and the lock screen show the same thumbnail as the app, decoded once.
 * Embedded artwork the player hands over as bytes is decoded with the
 * cache's subsampling. No Media3 `DataSourceBitmapLoader`: it always
 * builds an HTTP data source, which Crate cannot use (no INTERNET
 * permission) and Play flags as image downloading.
 */
@UnstableApi
class ArtworkBitmapLoader(private val cache: ArtworkCache) : BitmapLoader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        scope.future { ArtworkCache.decode(data) ?: throw IOException("undecodable artwork") }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = scope.future {
        val key = uri.lastPathSegment?.takeIf { uri.scheme == SCHEME } ?: throw IOException("not an album URI: $uri")
        cache.thumbnail(key) ?: throw IOException("no artwork for $key")
    }

    fun close() = scope.cancel()

    companion object {
        const val SCHEME = "crate"

        fun uri(key: String): Uri = Uri.parse("$SCHEME://album/$key")
    }
}

/**
 * content:// and file:// only. Media3's default includes an HTTP data
 * source, which Crate cannot use (no INTERNET permission); without a
 * default factory anywhere, R8 drops it.
 */
@UnstableApi
private fun localDataSources(context: Context): DataSource.Factory =
    DefaultDataSource.Factory(context, FileDataSource.Factory())
