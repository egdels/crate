// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import de.schliweb.crate.CrateApplication
import de.schliweb.crate.core.Album
import de.schliweb.crate.core.ReplayGain
import de.schliweb.crate.core.Track

/**
 * The media items the player gets: id, URI, the tags the notification
 * shows, the album's artwork URI and the loudness correction in the
 * extras. Built here for the view model (play, Play Next, restore on
 * launch) and the service (the system's playback resumption) alike.
 * [asAlbum] picks the album gain over the track gain, as ReplayGain
 * intends for listening to a record as a whole.
 */
@androidx.annotation.OptIn(UnstableApi::class)
fun mediaItems(app: CrateApplication, tracks: List<Track>, albums: List<Album>, asAlbum: Boolean): List<MediaItem> {
    val byPath = HashMap<String, Album>()
    for (album in albums) for (track in album.tracks) byPath[track.id] = album
    val albumMean = if (asAlbum) ReplayGain.albumMean(tracks) else null
    return tracks.map { track -> mediaItem(app, track, byPath[track.id], ReplayGain.playbackGain(track, asAlbum, albumMean)) }
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun mediaItem(app: CrateApplication, track: Track, album: Album?, gain: Double): MediaItem {
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
