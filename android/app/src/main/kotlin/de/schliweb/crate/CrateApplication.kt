// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate

import android.app.Application
import de.schliweb.crate.core.JsonFavoritesStore
import de.schliweb.crate.core.JsonLibraryStore
import de.schliweb.crate.core.Library
import de.schliweb.crate.library.AndroidArtworkReader
import de.schliweb.crate.library.AndroidMetadataReader
import de.schliweb.crate.library.ArtworkCache
import de.schliweb.crate.library.SafFileSource
import java.io.File

/**
 * Process-wide objects shared by the activity's view model and the
 * playback service: the library (one JSON index under the app's files)
 * the cover cache (thumbnails under the app's cache directory, which
 * the system may clear; they are rebuilt on demand) and the favorites.
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

    val artwork: ArtworkCache by lazy {
        ArtworkCache(File(cacheDir, "artwork"), AndroidArtworkReader(this))
    }
}
