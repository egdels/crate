// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate

import de.schliweb.crate.core.Bookmarks
import de.schliweb.crate.core.BookmarksStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Where each audiobook was left, as one value for the whole process:
 * the playback service sets the marks (it sees every position, with or
 * without the activity), the view model reads them to start a book where
 * it was left. The file is written behind it, in order, on one thread,
 * and only when a mark changed: songs alone never touch it.
 */
class SharedBookmarks(private val store: BookmarksStore) {
    private val _state = MutableStateFlow(Bookmarks())
    val state: StateFlow<Bookmarks> = _state.asStateFlow()
    val current: Bookmarks get() = _state.value

    private val loading = Mutex()
    @Volatile private var loaded = false

    @OptIn(ExperimentalCoroutinesApi::class)
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /** Reads the file once; later calls return at once. */
    suspend fun load() {
        loading.withLock {
            if (loaded) return
            val saved = withContext(Dispatchers.IO) { runCatching { store.load() }.getOrNull() }
            if (saved != null) _state.value = saved
            loaded = true
        }
    }

    /** Ignored until [load] is done: a change to the empty value would
     *  overwrite the file with nothing but itself. */
    fun update(change: (Bookmarks) -> Bookmarks) {
        if (!loaded) return
        val before = _state.value
        _state.update(change)
        val changed = _state.value
        if (changed != before) writer.launch { runCatching { store.save(changed) } }
    }
}
