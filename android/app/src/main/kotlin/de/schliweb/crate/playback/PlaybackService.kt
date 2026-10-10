// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import de.schliweb.crate.CrateApplication
import de.schliweb.crate.MainActivity
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
 */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
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
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setBitmapLoader(ArtworkBitmapLoader(this, (application as CrateApplication).artwork))
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

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
 * Anything else goes to Media3's own loader.
 */
@UnstableApi
class ArtworkBitmapLoader(context: Context, private val cache: ArtworkCache) : BitmapLoader {
    private val fallback = DataSourceBitmapLoader(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun supportsMimeType(mimeType: String): Boolean = fallback.supportsMimeType(mimeType)

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = fallback.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        if (uri.scheme != SCHEME) return fallback.loadBitmap(uri)
        val key = uri.lastPathSegment ?: return fallback.loadBitmap(uri)
        return scope.future { cache.thumbnail(key) ?: throw IOException("no artwork for $key") }
    }

    fun close() = scope.cancel()

    companion object {
        const val SCHEME = "crate"

        fun uri(key: String): Uri = Uri.parse("$SCHEME://album/$key")
    }
}
