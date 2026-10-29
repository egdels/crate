// Copyright (C) 2026 Christian Kierdorf
// SPDX-License-Identifier: GPL-3.0-or-later

package de.schliweb.crate.core

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * How one track is practised: its tempo, its key, its tuning and the
 * passage that repeats. Remembered per file, so a song comes back the way
 * it was left; a track nobody touched has the neutral settings and takes
 * no room in the store. The same model as the Mac's `PracticeSettings`,
 * and the same `practice.json` layout.
 *
 * Every value is clamped on construction, also when read from a file, so
 * the player is never asked for something it refuses.
 */
@Serializable(with = PracticeSettingsSerializer::class)
class PracticeSettings(
    tempo: Double = 1.0,
    semitones: Int = 0,
    cents: Int = 0,
    val loop: PracticeLoop? = null,
    loopOff: Boolean = false,
) {
    /** The loop is kept but not played: practising the passage in its
     *  context, then back to it, without setting A and B again. Never
     *  set without a loop. */
    val loopOff: Boolean = loop != null && loopOff

    /** The loop that plays: null while there is none or it is off. */
    val activeLoop: PracticeLoop? get() = if (loopOff) null else loop

    /** Whether the track plays other than recorded: another sound, or a
     *  passage repeating. */
    val changesPlayback: Boolean get() = changesSound || activeLoop != null

    /** Playback speed, 1 = as recorded; the key does not follow it. */
    val tempo: Double = tempo.coerceIn(TEMPO_MIN, TEMPO_MAX)
    val semitones: Int = semitones.coerceIn(SEMITONE_MIN, SEMITONE_MAX)
    /** Fine tuning on top of the semitones. */
    val cents: Int = cents.coerceIn(CENT_MIN, CENT_MAX)

    /** The whole pitch shift in cents. */
    val pitchCents: Int get() = semitones * 100 + cents

    /** The pitch shift as a frequency factor, what Media3's
     *  `PlaybackParameters` takes. */
    val pitchFactor: Float get() = 2.0.pow(pitchCents / 1200.0).toFloat()

    /** Whether the audio is played as recorded; the loop does not count. */
    val changesSound: Boolean get() = tempo != 1.0 || semitones != 0 || cents != 0

    val isNeutral: Boolean get() = this == NEUTRAL

    /** The concert pitch the tuning amounts to: what an A4 in the
     *  recording sounds like after the cents, ignoring the semitones. */
    val referencePitch: Double get() = 440 * 2.0.pow(cents / 1200.0)

    fun copy(
        tempo: Double = this.tempo, semitones: Int = this.semitones, cents: Int = this.cents,
        loop: PracticeLoop? = this.loop, loopOff: Boolean = this.loopOff,
    ) = PracticeSettings(tempo, semitones, cents, loop, loopOff)

    /** Tempo, key and tuning back to the recording; the loop stays. */
    fun resetSound(): PracticeSettings = PracticeSettings(loop = loop, loopOff = loopOff)

    override fun equals(other: Any?): Boolean =
        other is PracticeSettings && tempo == other.tempo && semitones == other.semitones &&
            cents == other.cents && loop == other.loop && loopOff == other.loopOff

    override fun hashCode(): Int = listOf(tempo, semitones, cents, loop, loopOff).hashCode()

    override fun toString(): String =
        "PracticeSettings(tempo=$tempo, semitones=$semitones, cents=$cents, loop=$loop, loopOff=$loopOff)"

    companion object {
        const val TEMPO_MIN = 0.5
        const val TEMPO_MAX = 2.0
        const val SEMITONE_MIN = -12
        const val SEMITONE_MAX = 12
        const val CENT_MIN = -50
        const val CENT_MAX = 50
        /** The steps the tempo moves in from the buttons. */
        const val TEMPO_STEP = 0.05
        val NEUTRAL = PracticeSettings()
    }
}

/** A passage between two points in the file, in seconds; build it with
 *  [PracticeLoop.of], which refuses one too short to be a passage. */
@Serializable
data class PracticeLoop(val start: Double, val end: Double) {
    val length: Double get() = end - start

    operator fun contains(time: Double): Boolean = time >= start && time < end

    /** Fitted into a file of the given length; null when nothing of it
     *  is left. An unknown length (0) leaves it alone. */
    fun clampedTo(duration: Double): PracticeLoop? =
        if (duration <= 0) this else of(min(start, duration), min(end, duration))

    companion object {
        /** Shorter loops are a slip of the finger, not a passage. */
        const val MINIMUM_LENGTH = 0.2

        /** null when the points are too close; reversed points are swapped. */
        fun of(start: Double, end: Double): PracticeLoop? {
            val a = max(0.0, min(start, end))
            val b = max(start, end)
            return if (b - a >= MINIMUM_LENGTH) PracticeLoop(a, b) else null
        }
    }
}

/** Reads and writes [PracticeSettings] through the clamping constructor,
 *  with every field optional. */
object PracticeSettingsSerializer : KSerializer<PracticeSettings> {
    @Serializable
    private data class Surrogate(
        val tempo: Double = 1.0,
        val semitones: Int = 0,
        val cents: Int = 0,
        val loop: PracticeLoop? = null,
        // Only when set, like the Mac, so older files read the same.
        @OptIn(ExperimentalSerializationApi::class)
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val loopOff: Boolean = false,
    )

    override val descriptor: SerialDescriptor = Surrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: PracticeSettings) =
        encoder.encodeSerializableValue(
            Surrogate.serializer(), Surrogate(value.tempo, value.semitones, value.cents, value.loop, value.loopOff))

    override fun deserialize(decoder: Decoder): PracticeSettings {
        val s = decoder.decodeSerializableValue(Surrogate.serializer())
        return PracticeSettings(s.tempo, s.semitones, s.cents, s.loop?.let { PracticeLoop.of(it.start, it.end) }, s.loopOff)
    }
}

/**
 * Every track's practice settings, by track id. Kept apart from the
 * library for the same reason as [Favorites]: the index is rebuilt on
 * rescans and too big to rewrite for one turn of a knob. Immutable;
 * neutral settings are not kept.
 */
data class PracticeBook(val entries: Map<String, PracticeSettings> = emptyMap()) {
    val size: Int get() = entries.size

    operator fun get(id: String): PracticeSettings = entries[id] ?: PracticeSettings.NEUTRAL

    fun with(id: String, settings: PracticeSettings): PracticeBook =
        PracticeBook(if (settings.isNeutral) entries - id else entries + (id to settings))

    /** An audiobook's tempo is the whole book's: sets it on the tracks
     *  [Skip] treats as spoken word or long among the given ones (an
     *  album), leaving their other settings and every other track alone. */
    fun withTempo(tempo: Double, book: List<Track>): PracticeBook =
        book.filter(Skip::isOffered).fold(this) { result, track -> result.with(track.id, result[track.id].copy(tempo = tempo)) }

    /** Forgets every track under the folder, for when the folder leaves
     *  the library. */
    fun removeAllUnder(folderPath: String): PracticeBook =
        PracticeBook(entries.filterKeys { !Paths.isUnder(it, folderPath) })

    companion object {
        fun of(entries: Map<String, PracticeSettings>) = PracticeBook(entries.filterValues { !it.isNeutral })
    }
}

interface PracticeStore {
    /** null when nothing has been saved yet. */
    fun load(): PracticeBook?
    fun save(book: PracticeBook)
}

/** `practice.json` next to the library's index, sorted by id so the same
 *  book gives the same file. Written to a sibling first and renamed into
 *  place, like the index. */
class JsonPracticeStore(val file: File) : PracticeStore {
    @Serializable
    private data class FileContents(
        val version: Int = CURRENT_VERSION,
        val tracks: Map<String, PracticeSettings> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    override fun load(): PracticeBook? {
        if (!file.exists()) return null
        return PracticeBook.of(json.decodeFromString(FileContents.serializer(), file.readText()).tracks)
    }

    override fun save(book: PracticeBook) {
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(json.encodeToString(FileContents.serializer(), FileContents(tracks = book.entries.toSortedMap())))
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) throw java.io.IOException("could not replace ${file.path}")
        }
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}
