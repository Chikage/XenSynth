package icu.ringona.xensynth.hexkeyboard.playback

import icu.ringona.xensynth.hexkeyboard.core.AxialCoordinate
import icu.ringona.xensynth.hexkeyboard.core.HexaKeyboardConfiguration
import icu.ringona.xensynth.hexkeyboard.core.HexaKeyboardLayoutEngine
import icu.ringona.xensynth.midi.ParsedScore
import icu.ringona.xensynth.midi.WaterfallNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardPlaybackTimelineTest {
    @Test
    fun `snapping changes only the visual coordinate`() {
        val score = scoreOf(note(audioPitch = 60.37))
        val layout = HexaKeyboardLayoutEngine.build(
            HexaKeyboardConfiguration.Default.copy(period = 53),
        )

        val mapped = score.snapToKeyboard(layout).notes.single()

        assertEquals(60.37, mapped.audioPitch, 0.000_001)
        val keyPitch = visualMidiPitch(layout.cellAt(mapped.coordinate)!!, layout.configuration.period)
        val minimumDistance = layout.cells.minOf {
            kotlin.math.abs(visualMidiPitch(it, layout.configuration.period) - 60.37)
        }
        assertEquals(minimumDistance, kotlin.math.abs(keyPitch - 60.37), 0.000_001)
    }

    @Test
    fun `upcoming fan grows toward note start and active note keeps its track`() {
        val timeline = scoreOf(note(audioPitch = 60.0, start = 2.0, end = 3.0, track = 7))
            .snapToKeyboard(HexaKeyboardLayoutEngine.build())

        val early = timeline.visualFrameAt(0.3, emptySet()).keys.values.single()
        val late = timeline.visualFrameAt(1.7, emptySet()).keys.values.single()
        val active = timeline.visualFrameAt(2.2, setOf(0)).keys.values.single()

        assertTrue(late.upcoming!!.progress > early.upcoming!!.progress)
        assertTrue(active.isActive)
        assertEquals(listOf(7), active.activeTracks)
    }

    @Test
    fun `completed note creates a short burst then disappears`() {
        val timeline = scoreOf(note(audioPitch = 60.0, start = 0.0, end = 1.0))
            .snapToKeyboard(HexaKeyboardLayoutEngine.build())

        val burst = timeline.visualFrameAt(1.1, emptySet()).keys.values.single()
        val finished = timeline.visualFrameAt(1.5, emptySet())

        assertFalse(burst.completedNotes.isEmpty())
        assertTrue(finished.keys.isEmpty())
    }

    @Test
    fun `consecutive hits on one key are marked for flashing`() {
        val score = scoreOf(
            note(audioPitch = 60.0, start = 0.0, end = 0.1),
            note(audioPitch = 60.0, start = 0.22, end = 0.4),
        )
        val timeline = score.snapToKeyboard(HexaKeyboardLayoutEngine.build())

        assertFalse(timeline.notes[0].repeatedHit)
        assertTrue(timeline.notes[1].repeatedHit)
        assertTrue(timeline.visualFrameAt(0.24, setOf(1)).keys.values.single().flash > 0f)
    }

    @Test
    fun `unsorted XenSynth notes are ordered while retaining source indices`() {
        val score = scoreOf(
            note(audioPitch = 60.0, start = 2.0, end = 3.0, track = 9),
            note(audioPitch = 60.0, start = 0.0, end = 1.0, track = 4),
        )

        val timeline = score.snapToKeyboard(HexaKeyboardLayoutEngine.build())

        assertEquals(listOf(1, 0), timeline.notes.map { it.scoreIndex })
        val active = timeline.visualFrameAt(0.5, setOf(1)).keys.values.single()
        assertEquals(listOf(4), active.activeTracks)
    }

    @Test
    fun `active score index query uses half open note intervals`() {
        val timeline = scoreOf(
            note(audioPitch = 60.0, start = 0.0, end = 2.0),
            note(audioPitch = 61.0, start = 1.0, end = 3.0),
            note(audioPitch = 62.0, start = 2.0, end = 2.0),
        ).snapToKeyboard(HexaKeyboardLayoutEngine.build())

        assertEquals(setOf(0), timeline.activeScoreIndicesAt(0.0))
        assertTrue(timeline.activeScoreIndicesAt(-0.1).isEmpty())
        assertEquals(setOf(0, 1), timeline.activeScoreIndicesAt(1.0))
        assertEquals(setOf(1), timeline.activeScoreIndicesAt(2.0))
        assertTrue(timeline.activeScoreIndicesAt(3.0).isEmpty())
        assertTrue(timeline.activeScoreIndicesAt(Double.NaN).isEmpty())
    }

    @Test
    fun `negative lead in previews the first note without marking it active`() {
        val timeline = scoreOf(note(audioPitch = 60.0, start = 0.0, end = 1.0))
            .snapToKeyboard(HexaKeyboardLayoutEngine.build())

        val frame = timeline.visualFrameAt(-0.25, timeline.activeScoreIndicesAt(-0.25))

        assertTrue(frame.keys.values.single().upcoming != null)
        assertFalse(frame.keys.values.single().isActive)
    }

    @Test
    fun `preview keeps only the nearest sixteen note markers`() {
        val notes = List(MAX_PLAYBACK_PREVIEW_NOTES + 8) { index ->
            KeyboardPlaybackNote(
                scoreIndex = index,
                coordinate = AxialCoordinate(q = index, r = 0),
                start = 0.1 + index * 0.01,
                end = 1.0,
                audioPitch = 60.0 + index,
                velocity = 100,
                track = 0,
                repeatedHit = false,
            )
        }
        val timeline = KeyboardPlaybackTimeline(
            notes = notes,
            notesByEnd = notes.sortedBy { it.end },
            duration = 1.0,
        )

        val previewIndices = timeline.visualFrameAt(0.0, emptySet())
            .keys
            .values
            .mapNotNull { it.upcoming?.note?.scoreIndex }
            .sorted()

        assertEquals(MAX_PLAYBACK_PREVIEW_NOTES, previewIndices.size)
        assertEquals((0 until MAX_PLAYBACK_PREVIEW_NOTES).toList(), previewIndices)
    }

    private fun scoreOf(vararg notes: WaterfallNote): ParsedScore = ParsedScore(
        title = "test",
        format = "test",
        ticksPerQuarter = 480,
        tempos = emptyList(),
        meters = emptyList(),
        tempoMap = emptyList(),
        rawEvents = emptyList(),
        notes = notes.toList(),
        longNotes = notes.filter { it.end - it.start >= 1.0 },
        duration = notes.maxOfOrNull { it.end } ?: 0.0,
    )

    private fun note(
        audioPitch: Double,
        start: Double = 0.0,
        end: Double = 1.0,
        track: Int = 0,
    ): WaterfallNote = WaterfallNote(
        startTick = (start * 480).toLong(),
        endTick = (end * 480).toLong(),
        start = start,
        end = end,
        pitch = audioPitch,
        midiPitch = audioPitch.toInt(),
        cents = (audioPitch - audioPitch.toInt()) * 100.0,
        velocity = 100,
        channel = 0,
        track = track,
        program = 0,
        bankMsb = 0,
        bankLsb = 0,
    )
}
