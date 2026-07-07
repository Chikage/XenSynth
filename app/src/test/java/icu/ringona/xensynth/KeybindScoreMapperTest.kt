package icu.ringona.xensynth

import icu.ringona.xensynth.midi.ParsedScore
import icu.ringona.xensynth.midi.WaterfallNote
import icu.ringona.xensynth.view.ScaleGuide
import org.junit.Assert.assertEquals
import org.junit.Test

class KeybindScoreMapperTest {
    @Test
    fun remapsScoreNotesWithKeybindAndLeavesMissingKeysAlone() {
        val guide = ScaleGuide.fromCustomProfile(
            profileName = "8afdo",
            marks = mapOf(203.91 to 0.8f),
            keybind = mapOf(
                1 to 0.0,
                2 to 203.91
            )
        )
        val score = scoreOf(
            note(midiPitch = 60, pitch = 60.0),
            note(midiPitch = 61, pitch = 61.0),
            note(midiPitch = 62, pitch = 62.0)
        )

        val remapped = score.withKeybind(guide)

        assertEquals(60.0, remapped.notes[0].pitch, 0.0001)
        assertEquals(60.0, remapped.notes[1].pitch, 0.0001)
        assertEquals(62.0391, remapped.notes[2].pitch, 0.0001)
        assertEquals(3.91, remapped.notes[2].cents, 0.0001)
        assertEquals(62.0391, remapped.longNotes.single().pitch, 0.0001)
    }

    private fun scoreOf(vararg notes: WaterfallNote): ParsedScore {
        return ParsedScore(
            title = "test",
            format = "test",
            ticksPerQuarter = 480,
            tempos = emptyList(),
            meters = emptyList(),
            tempoMap = emptyList(),
            rawEvents = emptyList(),
            notes = notes.toList(),
            longNotes = notes.takeLast(1),
            duration = notes.maxOfOrNull { it.end } ?: 0.0
        )
    }

    private fun note(midiPitch: Int, pitch: Double): WaterfallNote {
        return WaterfallNote(
            startTick = midiPitch.toLong(),
            endTick = midiPitch.toLong() + 480,
            start = midiPitch.toDouble(),
            end = midiPitch.toDouble() + 1.0,
            pitch = pitch,
            midiPitch = midiPitch,
            cents = (pitch - pitch.toInt()) * 100.0,
            velocity = 96,
            channel = 0,
            track = 0,
            program = 0,
            bankMsb = 0,
            bankLsb = 0
        )
    }
}
