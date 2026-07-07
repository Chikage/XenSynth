package icu.ringona.xensynth

import icu.ringona.xensynth.midi.ParsedScore
import icu.ringona.xensynth.midi.WaterfallNote
import icu.ringona.xensynth.view.ScaleGuide
import kotlin.math.roundToInt

internal fun ParsedScore.withKeybind(guide: ScaleGuide): ParsedScore {
    if (!guide.hasKeybind) {
        return this
    }
    val remappedNotes = notes
        .map { it.withKeybind(guide) }
        .sortedWith(compareBy<WaterfallNote> { it.start }.thenBy { it.pitch })
    val remappedLongNotes = longNotes
        .map { it.withKeybind(guide) }
        .sortedWith(compareBy<WaterfallNote> { it.start }.thenBy { it.pitch })
    return copy(
        notes = remappedNotes,
        longNotes = remappedLongNotes
    )
}

internal fun WaterfallNote.withKeybind(guide: ScaleGuide): WaterfallNote {
    val pitch = guide.keyboundPitchForMidiPitch(midiPitch) ?: return this
    return copy(
        pitch = pitch,
        cents = (pitch - pitch.roundToInt()) * 100.0
    )
}
