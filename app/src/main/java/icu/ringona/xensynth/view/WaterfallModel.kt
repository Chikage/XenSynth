package icu.ringona.xensynth.view

import icu.ringona.xensynth.midi.WaterfallNote
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

data class WaterfallDisplayState(
    val pixelsPerSecond: Double,
    val pitchZoomScale: Double,
    val pitchPanSemitones: Double,
    val waterfallOffsetCents: Double
)

data class WaterfallPreviewNote(
    val pitch: Double,
    val visualPitch: Double,
    val midiPitch: Int,
    val cents: Double,
    val velocity: Int,
    val track: Int
)

data class WaterfallVisibleTimeRange(
    val start: Double,
    val end: Double
)

object WaterfallMetrics {
    const val DRAWABLE_MIN_PITCH = 0
    const val DRAWABLE_MAX_PITCH = 127
    const val DRAWABLE_PITCH_RANGE = 127.0
    const val MIN_PITCH = 21
    const val MAX_PITCH = 108
    const val NOTE_RANGE = 87.0
    const val TIME_ZOOM_MIN = 60.0
    const val DEFAULT_PIXELS_PER_SECOND = 160.0
    const val TIME_ZOOM_MAX = 420.0
    const val PITCH_ZOOM_MIN = NOTE_RANGE / DRAWABLE_PITCH_RANGE
    const val PITCH_ZOOM_DEFAULT = 1.0
    const val PITCH_ZOOM_MAX = 7.25
    const val OFFSET_CENT_RANGE = 128.0
    const val NOTE_MIN_WIDTH_PX = 2f
    const val NOTE_MAX_WIDTH_PX = 7f
    const val NOTE_MIN_HEIGHT_PX = 4f
    const val INITIAL_NOTE_RULER_GAP_PX = 36f
    const val INITIAL_NOTE_GAP_PLAYHEAD_EPSILON = 0.001
    const val IMPACT_EXTRA_WIDTH = 3.0f
    const val C4_LABEL_RESERVED_HEIGHT_DP = 21f
    const val C4_LABEL_BOTTOM_PADDING_DP = 5f
    const val PITCH_CENTER = (MIN_PITCH + MAX_PITCH) / 2.0
    const val LOOKAHEAD_SECONDS = 6.5
    const val LOOKBACK_SECONDS = 1.5
    const val VISIBLE_EDGE_MARGIN_PX = 8f
    const val MANUAL_NOTE_MIN_SECONDS = 0.12
    const val MANUAL_NOTE_MIN_HEIGHT = 10f
    const val MANUAL_NOTE_FADE_DISTANCE = 620f
    const val MANUAL_NOTE_OFFSCREEN_MARGIN = 80f
    const val MANUAL_PITCH_SLOT_EPSILON = 0.0001
    const val PREVIEW_VISUAL_CENTS = 18.0
    const val RULER_VELOCITY_MAX_DEPTH = 0.8f
    const val RULER_MIN_VELOCITY = 24
    const val RULER_TOUCH_SLOP_DP = 18f
    const val RULER_ACTIVE_TOUCH_SLOP_DP = 30f
    const val RULER_PITCH_HYSTERESIS_CENTS = 18.0
    const val HIT_PARTICLE_CURSOR_EPSILON = 0.001
    const val HIT_PARTICLE_GRAVITY = 360f
    const val HIT_PARTICLE_MAX = 1200
    const val HIT_PARTICLE_LIFE_MIN = 0.34f
    const val HIT_PARTICLE_LIFE_RANDOM_RANGE = 0.36f
    const val KEY_IMPACT_LIFE = 0.26f
    const val OCTAVE_LINE_ALPHA = 96
    const val GRID_LINE_ALPHA = 42
    const val C_TICK_HEIGHT_RATIO = 0.84f
    const val C_TICK_ALPHA = 184
    const val WATERFALL_BACKGROUND_TOP_ALPHA = 36
    const val WATERFALL_BACKGROUND_MIDDLE_ALPHA = 30
    const val WATERFALL_BACKGROUND_BOTTOM_ALPHA = 46
    const val WATERFALL_BACKGROUND_HIGHLIGHT_ALPHA = 24
    const val WATERFALL_BACKGROUND_GLOW_ALPHA = 22
    const val WATERFALL_GLASS_TOP_ALPHA = 12
    const val WATERFALL_GLASS_MIDDLE_ALPHA = 18
    const val WATERFALL_GLASS_BOTTOM_ALPHA = 26
    const val WATERFALL_GLASS_GLEAM_ALPHA = 14

    fun visiblePitchRange(pitchZoomScale: Double): Double {
        return min(DRAWABLE_PITCH_RANGE, NOTE_RANGE / pitchZoomScale.coerceIn(PITCH_ZOOM_MIN, PITCH_ZOOM_MAX))
    }

    fun pitchMoveLimit(pitchZoomScale: Double): Double {
        return max(absPitchPanMin(pitchZoomScale), absPitchPanMax(pitchZoomScale))
    }

    fun pitchPanRange(pitchZoomScale: Double): Double {
        return pitchPanMax(pitchZoomScale) - pitchPanMin(pitchZoomScale)
    }

    fun coercePitchPan(pitchZoomScale: Double, pitchPanSemitones: Double): Double {
        return pitchPanSemitones.coerceIn(pitchPanMin(pitchZoomScale), pitchPanMax(pitchZoomScale))
    }

    private fun pitchPanMin(pitchZoomScale: Double): Double {
        val minCenter = DRAWABLE_MIN_PITCH + visiblePitchRange(pitchZoomScale) / 2.0
        return minCenter - PITCH_CENTER
    }

    private fun pitchPanMax(pitchZoomScale: Double): Double {
        val maxCenter = DRAWABLE_MAX_PITCH - visiblePitchRange(pitchZoomScale) / 2.0
        return maxCenter - PITCH_CENTER
    }

    private fun absPitchPanMin(pitchZoomScale: Double): Double {
        return kotlin.math.abs(pitchPanMin(pitchZoomScale))
    }

    private fun absPitchPanMax(pitchZoomScale: Double): Double {
        return kotlin.math.abs(pitchPanMax(pitchZoomScale))
    }

    fun keyImpactLife(noteDurationSeconds: Double?): Float {
        return noteEffectLife(KEY_IMPACT_LIFE, noteDurationSeconds)
    }

    fun hitParticleLife(baseLife: Float, noteDurationSeconds: Double?): Float {
        return noteEffectLife(baseLife, noteDurationSeconds)
    }

    fun hitParticleMotionScale(life: Float): Float {
        val sustain = ((life - HIT_PARTICLE_LIFE_MIN) / PARTICLE_SUSTAIN_SCALE_SECONDS).coerceIn(0f, 1f)
        return 1f - (1f - PARTICLE_MIN_MOTION_SCALE) * sustain
    }

    fun hitParticleGravityScale(life: Float): Float {
        val sustain = ((life - HIT_PARTICLE_LIFE_MIN) / PARTICLE_SUSTAIN_SCALE_SECONDS).coerceIn(0f, 1f)
        return 1f - (1f - PARTICLE_MIN_GRAVITY_SCALE) * sustain
    }

    private fun noteEffectLife(baseLife: Float, noteDurationSeconds: Double?): Float {
        val noteLife = noteDurationSeconds
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.toFloat()
            ?: 0f
        return max(baseLife, noteLife + NOTE_EFFECT_TAIL_SECONDS)
    }

    private const val NOTE_EFFECT_TAIL_SECONDS = 0.12f
    private const val PARTICLE_SUSTAIN_SCALE_SECONDS = 3.0f
    private const val PARTICLE_MIN_MOTION_SCALE = 0.28f
    private const val PARTICLE_MIN_GRAVITY_SCALE = 0.12f
}

data class WaterfallLayout(
    val playheadSeconds: Double,
    val pixelsPerSecond: Double,
    val pitchZoomScale: Double,
    val pitchPanSemitones: Double,
    val waterfallOffsetCents: Double,
    val density: Float
) {
    fun keyboardHeight(height: Float): Float {
        return min(118f * density, max(72f * density, height * 0.118f))
    }

    fun keyboardTop(height: Float): Float {
        return height - keyboardHeight(height)
    }

    fun renderedPitch(note: WaterfallNote): Double {
        return note.pitch + waterfallOffsetCents / 100.0
    }

    fun timeToY(time: Double, height: Float, playhead: Double = playheadSeconds): Float {
        return (keyboardTop(height) - (time - playhead) * pixelsPerSecond).toFloat()
    }

    fun pitchToX(pitch: Double, width: Float): Float {
        return (((pitch - visiblePitchMin()) / visiblePitchRange()) * width).toFloat()
    }

    fun xToPitch(x: Float, width: Float): Double {
        return visiblePitchMin() + (x / width.coerceAtLeast(1f)) * visiblePitchRange()
    }

    fun visiblePitchRange(): Double {
        return WaterfallMetrics.visiblePitchRange(pitchZoomScale)
    }

    fun pitchMoveLimit(): Double {
        return WaterfallMetrics.pitchMoveLimit(pitchZoomScale)
    }

    fun visiblePitchMin(): Double {
        return WaterfallMetrics.PITCH_CENTER +
            WaterfallMetrics.coercePitchPan(pitchZoomScale, pitchPanSemitones) -
            visiblePitchRange() / 2.0
    }

    fun visiblePitchMax(): Double {
        return WaterfallMetrics.PITCH_CENTER +
            WaterfallMetrics.coercePitchPan(pitchZoomScale, pitchPanSemitones) +
            visiblePitchRange() / 2.0
    }

    fun noteWidth(width: Float): Float {
        val semitoneWidth = width / WaterfallMetrics.NOTE_RANGE.toFloat()
        return max(
            WaterfallMetrics.NOTE_MIN_WIDTH_PX,
            min(WaterfallMetrics.NOTE_MAX_WIDTH_PX, semitoneWidth * 0.16f)
        )
    }

    fun visibleTimeRange(height: Float, playhead: Double = playheadSeconds): WaterfallVisibleTimeRange {
        val secondsPerPixel = 1.0 / pixelsPerSecond.coerceAtLeast(1.0)
        val waterfallHeightSeconds = keyboardTop(height).coerceAtLeast(0f) * secondsPerPixel
        val edgeMarginSeconds = WaterfallMetrics.VISIBLE_EDGE_MARGIN_PX * secondsPerPixel
        return WaterfallVisibleTimeRange(
            start = playhead - WaterfallMetrics.LOOKBACK_SECONDS - edgeMarginSeconds,
            end = playhead + max(
                WaterfallMetrics.LOOKAHEAD_SECONDS,
                waterfallHeightSeconds + edgeMarginSeconds
            )
        )
    }

    fun initialNoteDisplayPlayhead(notes: List<WaterfallNote>): Double {
        val firstStart = notes.firstOrNull()?.start ?: return playheadSeconds
        if (playheadSeconds < -WaterfallMetrics.INITIAL_NOTE_GAP_PLAYHEAD_EPSILON) {
            return playheadSeconds
        }
        if (playheadSeconds > WaterfallMetrics.INITIAL_NOTE_GAP_PLAYHEAD_EPSILON) {
            return playheadSeconds
        }
        val gapPx = WaterfallMetrics.INITIAL_NOTE_RULER_GAP_PX
        val currentGapPx = ((firstStart - playheadSeconds) * pixelsPerSecond).coerceAtLeast(0.0)
        if (currentGapPx >= gapPx) {
            return playheadSeconds
        }
        return firstStart - gapPx / pixelsPerSecond
    }
}

internal class WaterfallVisibleNoteIndex(
    private val notes: List<WaterfallNote>,
    bucketSeconds: Double = DEFAULT_BUCKET_SECONDS
) {
    private val bucketDuration = bucketSeconds.coerceAtLeast(MIN_BUCKET_SECONDS)
    private val buckets: Array<IntArray>
    private val longNoteIndices: IntArray
    private val seen = IntArray(notes.size)
    private val visible = ArrayList<Int>(128)
    private var visibleStamp = 0

    init {
        if (notes.isEmpty()) {
            buckets = emptyArray()
            longNoteIndices = IntArray(0)
        } else {
            val bucketCount = max(1, bucketOf(notes.maxOf { it.end.coerceAtLeast(0.0) }) + 1)
            val mutableBuckets = Array(bucketCount) { ArrayList<Int>() }
            val longNotes = ArrayList<Int>()
            notes.forEachIndexed { index, note ->
                if (!note.start.isFinite() || !note.end.isFinite()) {
                    return@forEachIndexed
                }
                val firstBucket = bucketOf(min(note.start, note.end))
                val lastBucket = min(bucketCount - 1, bucketOf(max(note.start, note.end)))
                if (lastBucket < firstBucket) {
                    return@forEachIndexed
                }
                if (lastBucket - firstBucket + 1 > MAX_BUCKETS_PER_NOTE) {
                    longNotes += index
                } else {
                    for (bucket in firstBucket..lastBucket) {
                        mutableBuckets[bucket] += index
                    }
                }
            }
            buckets = Array(bucketCount) { bucket -> mutableBuckets[bucket].toIntArray() }
            longNoteIndices = longNotes.toIntArray()
        }
    }

    fun visibleNoteIndices(visibleStart: Double, visibleEnd: Double): List<Int> {
        visible.clear()
        if (notes.isEmpty() || visibleEnd < 0.0 || visibleEnd < visibleStart) {
            return visible
        }
        val stamp = nextVisibleStamp()
        val firstBucket = bucketOf(visibleStart).coerceAtLeast(0)
        val lastBucket = min(bucketOf(visibleEnd), buckets.lastIndex)
        if (firstBucket <= lastBucket) {
            for (bucket in firstBucket..lastBucket) {
                buckets[bucket].forEach { index ->
                    collectVisibleIndex(index, visibleStart, visibleEnd, stamp)
                }
            }
        }
        longNoteIndices.forEach { index ->
            collectVisibleIndex(index, visibleStart, visibleEnd, stamp)
        }
        visible.sort()
        return visible
    }

    private fun collectVisibleIndex(index: Int, visibleStart: Double, visibleEnd: Double, stamp: Int) {
        if (index !in notes.indices || seen[index] == stamp) {
            return
        }
        seen[index] = stamp
        val note = notes[index]
        if (note.end >= visibleStart && note.start <= visibleEnd) {
            visible += index
        }
    }

    private fun nextVisibleStamp(): Int {
        if (visibleStamp == Int.MAX_VALUE) {
            seen.fill(0)
            visibleStamp = 1
        } else {
            visibleStamp++
        }
        return visibleStamp
    }

    private fun bucketOf(seconds: Double): Int {
        return floor(seconds.coerceAtLeast(0.0) / bucketDuration).toInt()
    }

    private companion object {
        private const val DEFAULT_BUCKET_SECONDS = 0.5
        private const val MIN_BUCKET_SECONDS = 0.05
        private const val MAX_BUCKETS_PER_NOTE = 64
    }
}
