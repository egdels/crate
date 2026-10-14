// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate

import de.schliweb.crate.core.History
import de.schliweb.crate.core.HistoryStore
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
 * What was played, as one value for the whole process: the playback
 * service records a track when it starts (it is the one that sees every
 * start, with or without the activity), the view model shows the list.
 * The file is written behind it, in order, on one thread; a failed
 * write is not worth a banner.
 */
class SharedHistory(private val store: HistoryStore) {
    private val _state = MutableStateFlow(History())
    val state: StateFlow<History> = _state.asStateFlow()

    private val loading = Mutex()
    private var loaded = false

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

    fun update(change: (History) -> History) {
        _state.update(change)
        val changed = _state.value
        writer.launch { runCatching { store.save(changed) } }
    }
}
