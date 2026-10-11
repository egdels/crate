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
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
        val player = ExoPlayer.Builder(this, DefaultMediaSourceFactory(localDataSources(this)))
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
            .setBitmapLoader(ArtworkBitmapLoader((application as CrateApplication).artwork))
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
