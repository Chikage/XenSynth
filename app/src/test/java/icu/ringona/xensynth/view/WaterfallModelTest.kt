package icu.ringona.xensynth.view

import icu.ringona.xensynth.midi.MeterEvent
import icu.ringona.xensynth.midi.ParsedScore
import icu.ringona.xensynth.midi.TempoEvent
import icu.ringona.xensynth.midi.TempoPoint
import icu.ringona.xensynth.midi.WaterfallNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaterfallModelTest {
    @Test
    fun defaultPitchWindowKeepsPianoRangeVisible() {
        val layout = layout()

        assertEquals(WaterfallMetrics.MIN_PITCH.toDouble(), layout.visiblePitchMin(), 0.0001)
        assertEquals(WaterfallMetrics.MAX_PITCH.toDouble(), layout.visiblePitchMax(), 0.0001)
    }

    @Test
    fun widestPitchWindowIsClampedToMidiRange() {
        val layout = layout(
            pitchZoomScale = WaterfallMetrics.PITCH_ZOOM_MIN,
            pitchPanSemitones = 0.0
        )

        assertEquals(WaterfallMetrics.DRAWABLE_MIN_PITCH.toDouble(), layout.visiblePitchMin(), 0.0001)
        assertEquals(WaterfallMetrics.DRAWABLE_MAX_PITCH.toDouble(), layout.visiblePitchMax(), 0.0001)
    }

    @Test
    fun pitchPanCannotMoveVisibleWindowOutsideMidiRange() {
        val low = layout(pitchPanSemitones = -999.0)
        val high = layout(pitchPanSemitones = 999.0)

        assertEquals(WaterfallMetrics.DRAWABLE_MIN_PITCH.toDouble(), low.visiblePitchMin(), 0.0001)
        assertEquals(WaterfallMetrics.DRAWABLE_MAX_PITCH.toDouble(), high.visiblePitchMax(), 0.0001)
    }

    @Test
    fun initialNoteDisplayPlayheadKeepsConfiguredGapFromRuler() {
        val layout = WaterfallLayout(
            playheadSeconds = 0.0,
            pixelsPerSecond = 100.0,
            pitchZoomScale = WaterfallMetrics.PITCH_ZOOM_DEFAULT,
            pitchPanSemitones = 0.0,
            waterfallOffsetCents = 0.0,
            density = 2f
        )
        val notes = listOf(note(start = 0.0, end = 0.5))

        val displayPlayhead = layout.initialNoteDisplayPlayhead(notes)
        val y = layout.timeToY(notes.first().start, height = 1000f, playhead = displayPlayhead)

        assertEquals(
            layout.keyboardTop(1000f) - WaterfallMetrics.INITIAL_NOTE_RULER_GAP_DP * layout.density,
            y,
            0.0001f
        )
    }

    @Test
    fun initialNoteDisplayPlayheadLeavesExistingLargerGapAlone() {
        val layout = WaterfallLayout(
            playheadSeconds = 0.0,
            pixelsPerSecond = 100.0,
            pitchZoomScale = WaterfallMetrics.PITCH_ZOOM_DEFAULT,
            pitchPanSemitones = 0.0,
            waterfallOffsetCents = 0.0,
            density = 1f
        )

        assertEquals(0.0, layout.initialNoteDisplayPlayhead(listOf(note(start = 0.2, end = 0.5))), 0.0001)
    }

    @Test
    fun initialNoteDisplayPlayheadKeepsNegativeLeadInScrolling() {
        val layout = WaterfallLayout(
            playheadSeconds = -0.08,
            pixelsPerSecond = 100.0,
            pitchZoomScale = WaterfallMetrics.PITCH_ZOOM_DEFAULT,
            pitchPanSemitones = 0.0,
            waterfallOffsetCents = 0.0,
            density = 1f
        )

        assertEquals(-0.08, layout.initialNoteDisplayPlayhead(listOf(note(start = 0.0, end = 0.5))), 0.0001)
    }

    @Test
    fun timelineDisplayPlayheadAlignsInitialMeasureAndFirstNote() {
        val layout = WaterfallLayout(
            playheadSeconds = 0.0,
            pixelsPerSecond = 100.0,
            pitchZoomScale = WaterfallMetrics.PITCH_ZOOM_DEFAULT,
            pitchPanSemitones = 0.0,
            waterfallOffsetCents = 0.0,
            density = 1f
        )
        val score = score(note(start = 0.0, end = 0.5))

        val timelinePlayhead = timelineDisplayPlayheadSeconds(layout, score, playbackActive = false)
        val measureY = layout.timeToY(0.0, height = 1000f, playhead = timelinePlayhead)
        val noteY = layout.timeToY(score.notes.first().start, height = 1000f, playhead = timelinePlayhead)

        assertEquals(noteY, measureY, 0.0001f)
        assertEquals(
            layout.keyboardTop(1000f) - WaterfallMetrics.INITIAL_NOTE_RULER_GAP_DP * layout.density,
            measureY,
            0.0001f
        )
    }

    @Test
    fun playbackActiveUsesActualPlayheadAtTimelineStart() {
        val layout = WaterfallLayout(
            playheadSeconds = 0.0,
            pixelsPerSecond = 100.0,
            pitchZoomScale = WaterfallMetrics.PITCH_ZOOM_DEFAULT,
            pitchPanSemitones = 0.0,
            waterfallOffsetCents = 0.0,
            density = 1f
        )
        val score = score(note(start = 0.0, end = 0.5))

        val timelinePlayhead = timelineDisplayPlayheadSeconds(layout, score, playbackActive = true)
        val startY = layout.timeToY(0.0, height = 1000f, playhead = timelinePlayhead)

        assertEquals(0.0, timelinePlayhead, 0.0001)
        assertEquals(layout.keyboardTop(1000f), startY, 0.0001f)
    }

    @Test
    fun negativeLeadInPositionsTimelineAboveRuler() {
        val layout = WaterfallLayout(
            playheadSeconds = -0.16,
            pixelsPerSecond = 100.0,
            pitchZoomScale = WaterfallMetrics.PITCH_ZOOM_DEFAULT,
            pitchPanSemitones = 0.0,
            waterfallOffsetCents = 0.0,
            density = 1f
        )

        val startY = layout.timeToY(0.0, height = 1000f)

        assertEquals(layout.keyboardTop(1000f) - 16f, startY, 0.0001f)
    }

    @Test
    fun visibleTimeRangeCoversWholeWaterfallAtSlowZoom() {
        val layout = layout(
            playheadSeconds = 12.0,
            pixelsPerSecond = WaterfallMetrics.TIME_ZOOM_MIN
        )
        val height = 1000f
        val range = layout.visibleTimeRange(height)
        val edgeMarginSeconds = WaterfallMetrics.VISIBLE_EDGE_MARGIN_PX / layout.pixelsPerSecond
        val topEdgeSeconds = layout.playheadSeconds + layout.keyboardTop(height) / layout.pixelsPerSecond

        assertEquals(layout.playheadSeconds - WaterfallMetrics.LOOKBACK_SECONDS - edgeMarginSeconds, range.start, 0.0001)
        assertEquals(topEdgeSeconds + edgeMarginSeconds, range.end, 0.0001)
        assertTrue(range.end > layout.playheadSeconds + WaterfallMetrics.LOOKAHEAD_SECONDS)
    }

    @Test
    fun visibleTimeRangeKeepsMinimumLookaheadAtFastZoom() {
        val layout = layout(
            playheadSeconds = 12.0,
            pixelsPerSecond = WaterfallMetrics.TIME_ZOOM_MAX
        )
        val range = layout.visibleTimeRange(height = 1000f)

        assertEquals(layout.playheadSeconds + WaterfallMetrics.LOOKAHEAD_SECONDS, range.end, 0.0001)
    }

    private fun layout(
        playheadSeconds: Double = 0.0,
        pixelsPerSecond: Double = WaterfallMetrics.DEFAULT_PIXELS_PER_SECOND,
        pitchZoomScale: Double = WaterfallMetrics.PITCH_ZOOM_DEFAULT,
        pitchPanSemitones: Double = 0.0,
        waterfallOffsetCents: Double = 0.0,
        density: Float = 1f
    ): WaterfallLayout {
        return WaterfallLayout(
            playheadSeconds = playheadSeconds,
            pixelsPerSecond = pixelsPerSecond,
            pitchZoomScale = pitchZoomScale,
            pitchPanSemitones = pitchPanSemitones,
            waterfallOffsetCents = waterfallOffsetCents,
            density = density
        )
    }

    private fun note(start: Double, end: Double): WaterfallNote {
        return WaterfallNote(
            startTick = 0L,
            endTick = 0L,
            start = start,
            end = end,
            pitch = 60.0,
            midiPitch = 60,
            cents = 0.0,
            velocity = 96,
            channel = 0,
            track = 0,
            program = 0,
            bankMsb = 0,
            bankLsb = 0
        )
    }

    private fun timelineDisplayPlayheadSeconds(
        layout: WaterfallLayout,
        score: ParsedScore?,
        playbackActive: Boolean
    ): Double {
        if (playbackActive) {
            return layout.playheadSeconds
        }
        return score?.notes?.let(layout::initialNoteDisplayPlayhead) ?: layout.playheadSeconds
    }

    private fun score(note: WaterfallNote): ParsedScore {
        return ParsedScore(
            title = "test",
            format = "test",
            ticksPerQuarter = 480,
            tempos = listOf(TempoEvent(tick = 0L, usPerQuarter = 500000.0)),
            meters = listOf(MeterEvent(tick = 0L, numerator = 4, denominator = 4)),
            tempoMap = listOf(TempoPoint(tick = 0L, second = 0.0, usPerQuarter = 500000.0)),
            rawEvents = emptyList(),
            notes = listOf(note),
            longNotes = emptyList(),
            duration = note.end
        )
    }
}
