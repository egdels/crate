// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate

import de.schliweb.crate.core.PracticeBook
import de.schliweb.crate.core.PracticeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
 * Every track's tempo, key, tuning and loop as one value for the whole
 * process: the view model changes it from the practice panel, the
 * playback service applies it to whatever item is playing, also after a
 * skip from the notification or a gapless transition. Like
 * [SharedFavorites], but the file is written half a second after the
 * last change, because a dragged slider sends dozens.
 */
class SharedPractice(private val store: PracticeStore) {
    private val _state = MutableStateFlow(PracticeBook())
    val state: StateFlow<PracticeBook> = _state.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val loading = Mutex()
    private var loaded = false

    @OptIn(ExperimentalCoroutinesApi::class)
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private var pendingWrite: Job? = null

    /** Reads the file once; later calls return at once. */
    suspend fun load() {
        loading.withLock {
            if (loaded) return
            val saved = withContext(Dispatchers.IO) { store.load() }
            if (saved != null) _state.value = saved
            loaded = true
        }
    }

    val current: PracticeBook get() = _state.value

    /** Applies the change now and writes the file shortly after. */
    fun update(change: (PracticeBook) -> PracticeBook) {
        _state.update(change)
        pendingWrite?.cancel()
        pendingWrite = writer.launch {
            delay(500)
            try {
                store.save(_state.value)
            } catch (e: Exception) {
                _errors.tryEmit("Could not save the practice settings: ${e.message}")
            }
        }
    }
}
