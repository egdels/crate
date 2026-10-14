// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate

import de.schliweb.crate.core.Favorites
import de.schliweb.crate.core.FavoritesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The hearts as one value for the whole process: the view model shows
 * them and changes them from the UI, the playback service shows one on
 * the notification and changes it from there. Both see every change at
 * once through [state]; the file is written behind it, in order, on one
 * thread. A failed write is reported through [errors] (the UI shows it
 * in its banner) and does not take the change back.
 */
class SharedFavorites(private val store: FavoritesStore) {
    private val _state = MutableStateFlow(Favorites())
    val state: StateFlow<Favorites> = _state.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val loading = Mutex()
    private var loaded = false

    @OptIn(ExperimentalCoroutinesApi::class)
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /** Reads the file once; later calls return at once. */
    suspend fun load() {
        loading.withLock {
            if (loaded) return
            val saved = withContext(Dispatchers.IO) { store.load() }
            if (saved != null) _state.value = saved
            loaded = true
        }
    }

    val current: Favorites get() = _state.value

    /** Applies the change now and writes the file behind it. */
    fun update(change: (Favorites) -> Favorites): Favorites {
        val changed = _state.updateAndGet(change)
        writer.launch {
            try {
                store.save(changed)
            } catch (e: Exception) {
                _errors.tryEmit("Could not save the favorites: ${e.message}")
            }
        }
        return changed
    }
}

private fun <T> MutableStateFlow<T>.updateAndGet(change: (T) -> T): T {
    update(change)
    return value
}
