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
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import de.schliweb.crate.CrateApplication
import de.schliweb.crate.MainActivity
import de.schliweb.crate.core.ReplayGain
import de.schliweb.crate.library.ArtworkCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
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
 */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val gain = GainProcessor()

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
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val decibels = mediaItem?.mediaMetadata?.extras?.getDouble(KEY_GAIN) ?: 0.0
                gain.factor = ReplayGain.factor(decibels)
                Log.i(TAG, "gain: ${"%.1f".format(decibels)} dB for ${mediaItem?.mediaMetadata?.title}")
            }

            override fun onShuffleModeEnabledChanged(enabled: Boolean) {
                if (enabled) reorder(player, queuedNext = IntRange.EMPTY, currentFirst = true)
            }
        })
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setCallback(callback)
            .setSessionActivity(openApp)
            .setBitmapLoader(ArtworkBitmapLoader((application as CrateApplication).artwork))
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    private val callback = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(COMMAND_QUEUED_NEXT, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands).build()
        }

        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle,
        ): ListenableFuture<SessionResult> {
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
    }

    override fun onDestroy() {
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
