// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * The loudness stage: multiplies the decoded 16-bit samples by the
 * current track's gain before they reach the audio track, separate from
 * the system volume. `player.volume` would only attenuate; Sound Check
 * also turns quiet tracks up. Other sample formats pass through
 * untouched (the processor reports itself inactive for them), so an odd
 * file still plays, only unlevelled.
 */
@UnstableApi
class GainProcessor : BaseAudioProcessor() {
    /** Linear factor; 1 leaves the audio as is. Set from the player thread,
     *  read on the audio thread. */
    @Volatile
    var factor: Float = 1f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        // The pipeline also queues an empty buffer, the shared EMPTY_BUFFER,
        // and replaceOutputBuffer(0) hands that same object back: copying
        // it into itself threw and stopped playback of every untagged
        // file in 0.6.0. Nothing to do, nothing to output.
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        val f = factor
        if (f == 1f) {
            out.put(inputBuffer)
        } else {
            while (inputBuffer.remaining() >= 2) {
                val sample = (inputBuffer.short * f).toInt().coerceIn(-32768, 32767)
                out.putShort(sample.toShort())
            }
            // An odd trailing byte cannot be a sample; drop it rather than loop forever.
            if (inputBuffer.hasRemaining()) inputBuffer.position(inputBuffer.limit())
        }
        out.flip()
    }
}
