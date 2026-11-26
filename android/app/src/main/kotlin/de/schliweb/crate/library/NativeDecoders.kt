// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.library

import java.nio.ByteBuffer

/**
 * A decoder in the app's own process (`src/main/cpp`, the one piece of
 * native code in the app, with [NativeAnalysis]): the loudness analysis decodes every file of
 * the library once, and the platform's decoders, in other processes,
 * took four to five times as long. Playback stays with Media3.
 */
internal interface NativeDecoder {
    /** A handle for [close], or 0 when [fd] does not hold a stream of
     *  this decoder's format. The descriptor has to stay open as long
     *  as the handle is used. */
    fun open(fd: Int): Long

    /** Fills the direct buffer [out] with interleaved 16-bit samples in
     *  native order; the number of samples, 0 at the end. */
    fun read(handle: Long, out: ByteBuffer): Int

    /** Known after the first [read] that returned samples. */
    fun rate(handle: Long): Int
    fun channels(handle: Long): Int
    fun close(handle: Long)

    companion object {
        /** False where the library cannot be loaded; the platform's
         *  decoders are used then. */
        val available: Boolean = runCatching { System.loadLibrary("crateaudio") }.isSuccess
    }
}

/**
 * The per-sample half of the loudness analysis (`analysis.c`): filter,
 * energy and peak on the interleaved PCM as a decoder left it. The
 * steps and blocks it hands out go to the core's `LoudnessMeter`
 * (`addSteps`, `notePeak`) and `WaveformBuilder` (`addBlock`), which
 * keep everything that is not a loop over samples.
 */
internal object NativeAnalysis {
    /** A handle, or 0. [coefficients] are the meter's
     *  `filterCoefficients`, the frame counts its step and the
     *  builder's block. */
    external fun open(channels: Int, coefficients: DoubleArray, stepFrames: Int, blockFrames: Int): Long

    /** [size] bytes of interleaved PCM in native order at [offset] of
     *  the direct [buffer], 16-bit or float; false when memory ran out. */
    external fun add(handle: Long, buffer: ByteBuffer, offset: Int, size: Int, floats: Boolean): Boolean

    /** Moves finished steps (each one sum per channel) and blocks into
     *  the arrays, as many as fit, oldest first: (steps shl 32) or
     *  blocks, 0 when none were left. */
    external fun drain(handle: Long, steps: DoubleArray, peaks: FloatArray, squares: DoubleArray): Long

    /** At the end of the audio: the frames of the block left unfinished,
     *  its values in the arrays' first elements; 0 when there is none. */
    external fun finish(handle: Long, peak: FloatArray, squares: DoubleArray): Int

    /** The largest sample so far, as a factor. */
    external fun peak(handle: Long): Float
    external fun close(handle: Long)

    /** Makes silence of every float among the first [size] bytes of
     *  the direct [buffer] that is no number or infinite: a float WAV
     *  may hold anything, and one such value spoils a measurement. */
    external fun zeroNonFinite(buffer: ByteBuffer, size: Int)
}

/** minimp3 (`mp3.c`). */
internal object Mp3Decoder : NativeDecoder {
    external override fun open(fd: Int): Long
    external override fun read(handle: Long, out: ByteBuffer): Int
    external override fun rate(handle: Long): Int
    external override fun channels(handle: Long): Int
    external override fun close(handle: Long)
}

/** dr_flac (`flac.c`). */
internal object FlacDecoder : NativeDecoder {
    external override fun open(fd: Int): Long
    external override fun read(handle: Long, out: ByteBuffer): Int
    external override fun rate(handle: Long): Int
    external override fun channels(handle: Long): Int
    external override fun close(handle: Long)
}
