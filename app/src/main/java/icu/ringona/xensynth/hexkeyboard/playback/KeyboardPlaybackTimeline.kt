package icu.ringona.xensynth.hexkeyboard.playback

import icu.ringona.xensynth.hexkeyboard.core.AxialCoordinate
import icu.ringona.xensynth.hexkeyboard.core.HexKey
import icu.ringona.xensynth.hexkeyboard.core.HexaKeyboardLayout
import icu.ringona.xensynth.midi.ParsedScore
import icu.ringona.xensynth.midi.WaterfallNote
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

internal const val PLAYBACK_PREVIEW_SECONDS_MIN = 0.0
internal const val PLAYBACK_PREVIEW_SECONDS_MAX = 3.0
internal const val PLAYBACK_PREVIEW_SECONDS = 1.8
internal const val PLAYBACK_COMPLETION_BURST_SECONDS = 0.34
internal const val PLAYBACK_REPEAT_WINDOW_SECONDS = 0.42
private const val PLAYBACK_REPEAT_GAP_SECONDS = 0.18

data class KeyboardPlaybackNote(
    val scoreIndex: Int,
    val coordinate: AxialCoordinate,
    val start: Double,
    val end: Double,
    val audioPitch: Double,
    val velocity: Int,
    val track: Int,
    val repeatedHit: Boolean,
)

data class KeyboardPlaybackTimeline(
    val notes: List<KeyboardPlaybackNote>,
    val notesByEnd: List<KeyboardPlaybackNote>,
    val duration: Double,
) {
    private val activeNoteIndex = ActiveNoteIntervalIndex(notes)

    internal fun queryActiveScoreIndices(positionSeconds: Double): Set<Int> =
        activeNoteIndex.query(positionSeconds)

    companion object {
        val Empty = KeyboardPlaybackTimeline(emptyList(), emptyList(), 0.0)
    }
}

/**
 * Returns source score indices active at [positionSeconds] without scanning the full score.
 * Note intervals are half-open: start is inclusive and end is exclusive.
 */
fun KeyboardPlaybackTimeline.activeScoreIndicesAt(positionSeconds: Double): Set<Int> =
    queryActiveScoreIndices(positionSeconds)

data class UpcomingPlaybackNote(
    val note: KeyboardPlaybackNote,
    val progress: Float,
)

data class CompletedPlaybackNote(
    val note: KeyboardPlaybackNote,
    val progress: Float,
)

data class PlaybackKeyVisual(
    val upcoming: UpcomingPlaybackNote? = null,
    val activeNotes: List<KeyboardPlaybackNote> = emptyList(),
    val completedNotes: List<CompletedPlaybackNote> = emptyList(),
    val flash: Float = 0f,
) {
    val activeTracks: List<Int>
        get() = activeNotes.asSequence().map { it.track }.distinct().sorted().toList()

    val isActive: Boolean get() = activeNotes.isNotEmpty()
}

data class PlaybackVisualFrame(
    val keys: Map<AxialCoordinate, PlaybackKeyVisual>,
) {
    companion object {
        val Empty = PlaybackVisualFrame(emptyMap())
    }
}

/**
 * Maps score notes to the nearest visible key. The source pitch is retained unchanged;
 * equal-temperament MIDI values derived from key steps are used only for visual matching.
 */
fun ParsedScore.snapToKeyboard(
    layout: HexaKeyboardLayout,
    pitchForKey: (HexKey) -> Double? = { key ->
        visualMidiPitch(key, layout.configuration.period)
    }
): KeyboardPlaybackTimeline {
    if (notes.isEmpty() || layout.cells.isEmpty()) return KeyboardPlaybackTimeline.Empty
    val pitchIndex = KeyboardPitchIndex(layout.cells, pitchForKey)
    val mapped = ArrayList<KeyboardPlaybackNote>(notes.size)
    val previousByCoordinate = mutableMapOf<AxialCoordinate, KeyboardPlaybackNote>()

    notes.withIndex()
        .sortedWith(
            compareBy<IndexedValue<WaterfallNote>> { it.value.start }
                .thenBy { it.index },
        )
        .forEach { (index, note) ->
            if (!note.pitch.isFinite()) return@forEach
            val key = pitchIndex.nearest(note.pitch) ?: return@forEach
            val previous = previousByCoordinate[key.coordinate]
            val repeated = previous != null && (
                note.start - previous.start <= PLAYBACK_REPEAT_WINDOW_SECONDS ||
                    note.start - previous.end <= PLAYBACK_REPEAT_GAP_SECONDS
                )
            val mappedNote = KeyboardPlaybackNote(
                scoreIndex = index,
                coordinate = key.coordinate,
                start = note.start,
                end = note.end.coerceAtLeast(note.start),
                audioPitch = note.pitch,
                velocity = note.velocity,
                track = note.track,
                repeatedHit = repeated,
            )
            mapped += mappedNote
            previousByCoordinate[key.coordinate] = mappedNote
        }

    return KeyboardPlaybackTimeline(
        notes = mapped,
        notesByEnd = mapped.sortedWith(compareBy<KeyboardPlaybackNote> { it.end }.thenBy { it.scoreIndex }),
        duration = duration,
    )
}

/**
 * Rewrites score pitches to the nearest visible key while retaining note timing and metadata.
 */
internal fun ParsedScore.snapPlaybackPitchesToKeyboard(
    layout: HexaKeyboardLayout,
    pitchForKey: (HexKey) -> Double? = { key ->
        visualMidiPitch(key, layout.configuration.period)
    }
): ParsedScore {
    if (notes.isEmpty() || layout.cells.isEmpty()) return this
    val pitchIndex = KeyboardPitchIndex(layout.cells, pitchForKey)

    fun WaterfallNote.snapped(): WaterfallNote {
        val snappedPitch = pitchIndex.nearestPitch(pitch) ?: return this
        if (snappedPitch == pitch) return this
        return copy(
            pitch = snappedPitch,
            cents = (snappedPitch - snappedPitch.roundToInt()) * 100.0,
        )
    }

    return copy(
        notes = notes
            .map { it.snapped() }
            .sortedWith(compareBy<WaterfallNote> { it.start }.thenBy { it.pitch }),
        longNotes = longNotes
            .map { it.snapped() }
            .sortedWith(compareBy<WaterfallNote> { it.start }.thenBy { it.pitch }),
    )
}

fun KeyboardPlaybackTimeline.visualFrameAt(
    positionSeconds: Double,
    activeScoreIndices: Set<Int>,
    previewSeconds: Double = PLAYBACK_PREVIEW_SECONDS,
): PlaybackVisualFrame {
    if (notes.isEmpty()) return PlaybackVisualFrame.Empty
    if (!positionSeconds.isFinite()) return PlaybackVisualFrame.Empty
    val position = positionSeconds
    val previewWindowSeconds = previewSeconds
        .takeIf { it.isFinite() }
        ?.coerceIn(PLAYBACK_PREVIEW_SECONDS_MIN, PLAYBACK_PREVIEW_SECONDS_MAX)
        ?: PLAYBACK_PREVIEW_SECONDS
    val builders = mutableMapOf<AxialCoordinate, PlaybackKeyVisualBuilder>()

    if (previewWindowSeconds > 0.0) {
        val upcomingStart = notes.upperBoundByStart(position)
        val upcomingEnd = notes.upperBoundByStart(position + previewWindowSeconds)
        for (index in upcomingStart until upcomingEnd) {
            val note = notes[index]
            val delta = (note.start - position).coerceAtLeast(0.0)
            val progress = (1.0 - delta / previewWindowSeconds).toFloat().coerceIn(0f, 1f)
            val builder = builders.getOrPut(note.coordinate, ::PlaybackKeyVisualBuilder)
            val current = builder.upcoming
            if (current == null || note.start < current.note.start) {
                builder.upcoming = UpcomingPlaybackNote(note, progress)
            }
        }
    }

    activeScoreIndices.forEach { scoreIndex ->
        val note = noteForScoreIndex(scoreIndex) ?: return@forEach
        builders.getOrPut(note.coordinate, ::PlaybackKeyVisualBuilder).active += note
    }

    val completedStart = notesByEnd.lowerBoundByEnd(position - PLAYBACK_COMPLETION_BURST_SECONDS)
    val completedEnd = notesByEnd.upperBoundByEnd(position)
    for (index in completedStart until completedEnd) {
        val note = notesByEnd[index]
        val age = (position - note.end).coerceAtLeast(0.0)
        val progress = (age / PLAYBACK_COMPLETION_BURST_SECONDS).toFloat().coerceIn(0f, 1f)
        val completed = builders.getOrPut(note.coordinate, ::PlaybackKeyVisualBuilder).completed
        if (completed.size < MAX_COMPLETED_NOTES_PER_KEY) {
            completed += CompletedPlaybackNote(note, progress)
        }
    }

    val recentStart = notes.lowerBoundByStart(position - PLAYBACK_REPEAT_WINDOW_SECONDS)
    val recentEnd = notes.upperBoundByStart(position)
    val recentHitCounts = mutableMapOf<AxialCoordinate, Int>()
    for (index in recentStart until recentEnd) {
        val note = notes[index]
        recentHitCounts[note.coordinate] = (recentHitCounts[note.coordinate] ?: 0) + 1
        val age = (position - note.start).coerceAtLeast(0.0)
        val impact = exp(-age * 12.0).toFloat()
        val builder = builders.getOrPut(note.coordinate, ::PlaybackKeyVisualBuilder)
        builder.flash = maxOf(builder.flash, impact)
    }

    recentHitCounts.forEach { (coordinate, count) ->
        val builder = builders[coordinate] ?: return@forEach
        val repeatedActive = builder.active.any { it.repeatedHit }
        if (count >= 2 || repeatedActive) {
            val pulse = (0.5 + 0.5 * sin(position * PI * 18.0)).toFloat()
            builder.flash = maxOf(builder.flash, 0.34f + pulse * 0.66f)
        }
    }

    return PlaybackVisualFrame(
        keys = builders.mapValues { (_, builder) -> builder.build() },
    )
}

private class PlaybackKeyVisualBuilder {
    var upcoming: UpcomingPlaybackNote? = null
    val active = mutableListOf<KeyboardPlaybackNote>()
    val completed = mutableListOf<CompletedPlaybackNote>()
    var flash: Float = 0f

    fun build(): PlaybackKeyVisual = PlaybackKeyVisual(
        upcoming = upcoming,
        activeNotes = active.sortedWith(compareBy<KeyboardPlaybackNote> { it.track }.thenBy { it.scoreIndex }),
        completedNotes = completed,
        flash = flash.coerceIn(0f, 1f),
    )
}

private class KeyboardPitchIndex(
    keys: List<HexKey>,
    pitchForKey: (HexKey) -> Double?
) {
    private val candidates: List<PitchCandidate> = keys
        .asSequence()
        .mapNotNull { key -> pitchForKey(key)?.let { pitch -> key to pitch } }
        .filter { (_, pitch) -> pitch.isFinite() }
        .groupBy { (_, pitch) -> pitch }
        .map { (pitch, equivalentKeys) ->
            PitchCandidate(
                pitch = pitch,
                key = equivalentKeys
                    .map { (key, _) -> key }
                    .minWithOrNull(
                        compareBy<HexKey> { it.center.x * it.center.x + it.center.y * it.center.y }
                            .thenBy { it.coordinate.q }
                            .thenBy { it.coordinate.r },
                    )!!,
            )
        }
        .sortedBy { it.pitch }
        .toList()

    fun nearest(audioPitch: Double): HexKey? = nearestCandidate(audioPitch)?.key

    fun nearestPitch(audioPitch: Double): Double? = nearestCandidate(audioPitch)?.pitch

    private fun nearestCandidate(audioPitch: Double): PitchCandidate? {
        if (candidates.isEmpty() || !audioPitch.isFinite()) return null
        var low = 0
        var high = candidates.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (candidates[middle].pitch < audioPitch) low = middle + 1 else high = middle
        }
        val above = candidates.getOrNull(low)
        val below = candidates.getOrNull(low - 1)
        return when {
            above == null -> below
            below == null -> above
            abs(above.pitch - audioPitch) < abs(audioPitch - below.pitch) -> above
            abs(above.pitch - audioPitch) > abs(audioPitch - below.pitch) -> below
            centerDistanceSquared(above.key) < centerDistanceSquared(below.key) -> above
            centerDistanceSquared(above.key) > centerDistanceSquared(below.key) -> below
            above.key.coordinate.q < below.key.coordinate.q -> above
            above.key.coordinate.q > below.key.coordinate.q -> below
            above.key.coordinate.r <= below.key.coordinate.r -> above
            else -> below
        }
    }

    private fun centerDistanceSquared(key: HexKey): Double =
        key.center.x * key.center.x + key.center.y * key.center.y

    private data class PitchCandidate(val pitch: Double, val key: HexKey)
}

private class ActiveNoteIntervalIndex(notes: List<KeyboardPlaybackNote>) {
    private val notesByStart = notes.sortedWith(
        compareBy<KeyboardPlaybackNote> { it.start }.thenBy { it.scoreIndex },
    )
    private val maximumEndTree = DoubleArray(maxOf(1, notesByStart.size * 4)) {
        Double.NEGATIVE_INFINITY
    }

    init {
        if (notesByStart.isNotEmpty()) build(node = 1, left = 0, right = notesByStart.size)
    }

    fun query(positionSeconds: Double): Set<Int> {
        if (notesByStart.isEmpty() || !positionSeconds.isFinite()) return emptySet()
        val position = positionSeconds
        val startLimit = notesByStart.upperBoundByStart(position)
        if (startLimit == 0 || maximumEndTree[1] <= position) return emptySet()

        return LinkedHashSet<Int>().also { active ->
            collect(
                node = 1,
                left = 0,
                right = notesByStart.size,
                startLimit = startLimit,
                position = position,
                destination = active,
            )
        }
    }

    private fun build(node: Int, left: Int, right: Int): Double {
        if (right - left == 1) {
            return notesByStart[left].end.also { maximumEndTree[node] = it }
        }
        val middle = (left + right) ushr 1
        val maximumEnd = maxOf(
            build(node * 2, left, middle),
            build(node * 2 + 1, middle, right),
        )
        maximumEndTree[node] = maximumEnd
        return maximumEnd
    }

    private fun collect(
        node: Int,
        left: Int,
        right: Int,
        startLimit: Int,
        position: Double,
        destination: MutableSet<Int>,
    ) {
        if (left >= startLimit || maximumEndTree[node] <= position) return
        if (right - left == 1) {
            destination += notesByStart[left].scoreIndex
            return
        }

        val middle = (left + right) ushr 1
        collect(node * 2, left, middle, startLimit, position, destination)
        collect(node * 2 + 1, middle, right, startLimit, position, destination)
    }
}

private fun KeyboardPlaybackTimeline.noteForScoreIndex(scoreIndex: Int): KeyboardPlaybackNote? {
    val direct = notes.getOrNull(scoreIndex)
    return if (direct?.scoreIndex == scoreIndex) direct else notes.firstOrNull { it.scoreIndex == scoreIndex }
}

private fun List<KeyboardPlaybackNote>.lowerBoundByStart(value: Double): Int =
    lowerBound(value) { it.start }

private fun List<KeyboardPlaybackNote>.upperBoundByStart(value: Double): Int =
    upperBound(value) { it.start }

private fun List<KeyboardPlaybackNote>.lowerBoundByEnd(value: Double): Int =
    lowerBound(value) { it.end }

private fun List<KeyboardPlaybackNote>.upperBoundByEnd(value: Double): Int =
    upperBound(value) { it.end }

private inline fun <T> List<T>.lowerBound(value: Double, selector: (T) -> Double): Int {
    var low = 0
    var high = size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (selector(this[middle]) < value) low = middle + 1 else high = middle
    }
    return low
}

private inline fun <T> List<T>.upperBound(value: Double, selector: (T) -> Double): Int {
    var low = 0
    var high = size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (selector(this[middle]) <= value) low = middle + 1 else high = middle
    }
    return low
}

internal fun visualMidiPitch(key: HexKey, period: Int): Double {
    if (period <= 0) return Double.NaN
    return 60.0 + key.step.toDouble() * 12.0 / period.toDouble()
}

private const val MAX_COMPLETED_NOTES_PER_KEY = 4
