// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate

import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import de.schliweb.crate.ui.CrateScreen
import de.schliweb.crate.ui.CrateTheme

class MainActivity : ComponentActivity() {
    private val model: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The hardware volume keys adjust the music, not the ringer.
        volumeControlStream = AudioManager.STREAM_MUSIC
        setContent {
            CrateTheme {
                CrateScreen(model)
            }
        }
        // Only a fresh launch: a recreated activity (after process death,
        // or reopened from Recents) gets the original VIEW intent again,
        // whose file has played already and whose grant may be gone.
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromHistory) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** "Open with Crate" from a file manager. */
    private fun handle(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let(model::open)
            intent.action = null
        }
    }
}
