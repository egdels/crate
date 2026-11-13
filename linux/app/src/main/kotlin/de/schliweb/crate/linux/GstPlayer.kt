// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.linux

import de.schliweb.crate.core.ReplayGain
import de.schliweb.crate.core.Track
import org.freedesktop.gstreamer.Bus
import org.freedesktop.gstreamer.ElementFactory
import org.freedesktop.gstreamer.Format
import org.freedesktop.gstreamer.State
import org.freedesktop.gstreamer.elements.PlayBin
import org.freedesktop.gstreamer.event.SeekFlags
import org.freedesktop.gstreamer.message.MessageType
import java.net.URI
import java.util.EnumSet

/**
 * The queue and its playback through one GStreamer playbin. Gapless the
 * way playbin offers it: on `about-to-finish` the next URI is set while
 * the current one still plays, and the switch shows up on the bus as
 * `stream-start`. The loudness gain is a `volume` element in the
 * playbin's audio filter slot; the user's volume is the playbin's own,
 * a separate stage as on the other platforms.
 *
 * Callbacks arrive on GStreamer's threads; [Listener] implementations
 * hop to the UI thread themselves.
 */
class GstPlayer(private val listener: Listener) {
    interface Listener {
        /** The track at [index] of the queue started to play. */
        fun started(index: Int)
        /** The queue played to its end. */
        fun finished()
        fun failed(message: String)
    }

    private val playbin = PlayBin("crate")
    private val gain = ElementFactory.make("volume", "gain")

    @Volatile private var queue: List<Track> = emptyList()
    @Volatile private var gains: List<Double> = emptyList()
    @Volatile private var index = -1
    /** The index handed to playbin on about-to-finish, not audible yet. */
    @Volatile private var pending = -1

    val isLoaded: Boolean get() = index >= 0

    init {
        playbin.setVideoSink(ElementFactory.make("fakesink", "video"))
        playbin.set("audio-filter", gain)
        playbin.connect(PlayBin.ABOUT_TO_FINISH { bin ->
            val next = index + 1
            if (next < queue.size) {
                pending = next
                bin.setURI(URI(queue[next].uri))
            }
        })
        val bus = playbin.bus
        bus.connect(Bus.MESSAGE { _, message ->
            if (message.type == MessageType.STREAM_START && pending >= 0) {
                index = pending
                pending = -1
                // The switch is reported once the new stream reaches the
                // sink, so the first moments of it passed the filter at
                // the old gain. Fine for a prototype; exact would be a
                // pad probe on the filter for the stream-start event.
                applyGain()
                listener.started(index)
            }
        })
        bus.connect(Bus.EOS { _ ->
            playbin.setState(State.READY)
            listener.finished()
        })
        bus.connect(Bus.ERROR { _, _, text -> listener.failed(text) })
    }

    /** Replaces the queue and plays [start]. [asAlbum] picks album gain,
     *  as `ReplayGain.playbackGain` decides on the other platforms. */
    fun play(tracks: List<Track>, start: Int, asAlbum: Boolean) {
        val mean = if (asAlbum) ReplayGain.albumMean(tracks) else null
        queue = tracks
        gains = tracks.map { ReplayGain.playbackGain(it, asAlbum, mean) }
        jump(start)
    }

    fun pause() { playbin.setState(State.PAUSED) }

    fun resume() { playbin.setState(State.PLAYING) }

    fun next() { if (index + 1 < queue.size) jump(index + 1) }

    /** Back to the start of the track after the first three seconds,
     *  otherwise to the previous one. */
    fun previous() {
        if (position() > 3 || index == 0) seek(0.0) else if (index > 0) jump(index - 1)
    }

    fun seek(seconds: Double) {
        playbin.seekSimple(Format.TIME, EnumSet.of(SeekFlags.FLUSH, SeekFlags.ACCURATE), (seconds * 1e9).toLong())
    }

    fun position(): Double = playbin.queryPosition(Format.TIME).takeIf { it >= 0 }?.let { it / 1e9 } ?: 0.0

    fun setVolume(linear: Double) { playbin.volume = linear }

    fun dispose() {
        playbin.setState(State.NULL)
        playbin.dispose()
    }

    private fun jump(to: Int) {
        playbin.setState(State.READY)
        index = to
        pending = -1
        applyGain()
        playbin.setURI(URI(queue[to].uri))
        playbin.setState(State.PLAYING)
        listener.started(to)
    }

    private fun applyGain() {
        val decibels = gains.getOrNull(index) ?: 0.0
        gain.set("volume", ReplayGain.factor(decibels).toDouble())
    }
}
