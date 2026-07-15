package icu.ringona.xensynth.audio

import icu.ringona.xensynth.FakeNativeAudio
import icu.ringona.xensynth.midi.ParsedScore
import icu.ringona.xensynth.midi.WaterfallNote
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeAudioSchedulerTest {
    @Test
    fun resetPlacesCursorAtFirstAudibleNote() {
        val scheduler = NativeAudioScheduler(FakeCallbacks(), FakeNativeAudio())
        val score = scoreOf(
            note(start = 0.0, end = 0.20, startTick = 0, endTick = 20),
            note(start = 0.30, end = 0.55, startTick = 30, endTick = 55)
        )

        scheduler.reset(score, playheadSeconds = 0.21)

        val cursor = NativeAudioScheduler::class.java
            .getDeclaredField("audioCursor")
            .apply { isAccessible = true }
            .getInt(scheduler)

        assertEquals(1, cursor)
    }

    @Test
    fun scheduleRestartsStreamAndStartsNotesWithinLookahead() {
        val callbacks = FakeCallbacks(canUseNativeAudio = true)
        val audio = FakeNativeAudio().apply {
            started = false
            restartResult = true
            nextNoteId = 42
        }
        val scheduler = NativeAudioScheduler(callbacks, audio)
        val score = scoreOf(
            note(start = 0.05, end = 0.40, startTick = 5, endTick = 40, pitch = 60.25)
        )

        scheduler.reset(score, playheadSeconds = 0.0)
        scheduler.schedule(score, playheadSeconds = 0.0, speed = 1.0, playing = true)

        assertEquals(1, audio.restartCalls)
        assertEquals(true, callbacks.lastReady)
        assertEquals(1, audio.noteOns.size)
        assertEquals(60, audio.noteOns[0].key)
        assertEquals(25f, audio.noteOns[0].cents, 0.001f)
        assertEquals(0.05, audio.noteOns[0].delaySeconds, 0.0001)
    }

    @Test
    fun scheduleDelaysTimelineStartDuringNegativeLeadIn() {
        val callbacks = FakeCallbacks(canUseNativeAudio = true)
        val audio = FakeNativeAudio().apply {
            started = true
            nextNoteId = 12
        }
        val scheduler = NativeAudioScheduler(callbacks, audio)
        val score = scoreOf(
            note(start = 0.0, end = 0.40, startTick = 0, endTick = 40)
        )

        scheduler.reset(score, playheadSeconds = -0.12)
        scheduler.schedule(score, playheadSeconds = -0.12, speed = 1.0, playing = true)

        assertEquals(1, audio.noteOns.size)
        assertEquals(0.12, audio.noteOns[0].delaySeconds, 0.0001)
    }

    @Test
    fun scheduleStopsActiveNotesAfterTheyEnd() {
        val callbacks = FakeCallbacks(canUseNativeAudio = true)
        val audio = FakeNativeAudio().apply {
            started = true
            nextNoteId = 7
        }
        val scheduler = NativeAudioScheduler(callbacks, audio)
        val score = scoreOf(
            note(start = 0.0, end = 0.10, startTick = 0, endTick = 10)
        )

        scheduler.reset(score, playheadSeconds = 0.0)
        scheduler.schedule(score, playheadSeconds = 0.0, speed = 1.0, playing = true)
        scheduler.schedule(score, playheadSeconds = 0.12, speed = 1.0, playing = true)

        assertEquals(listOf(7), audio.noteOffs)
    }

    @Test
    fun scheduleDoesNothingWhenPlaybackUnavailable() {
        val callbacks = FakeCallbacks(canUseNativeAudio = false)
        val audio = FakeNativeAudio().apply {
            started = true
        }
        val scheduler = NativeAudioScheduler(callbacks, audio)
        val score = scoreOf(
            note(start = 0.0, end = 0.10, startTick = 0, endTick = 10)
        )

        scheduler.reset(score, playheadSeconds = 0.0)
        scheduler.schedule(score, playheadSeconds = 0.0, speed = 1.0, playing = true)

        assertEquals(0, audio.noteOns.size)
    }

    @Test
    fun stopDoesNotSilenceVoicesOwnedByOtherInputsAfterSchedulingFailure() {
        val callbacks = FakeCallbacks(canUseNativeAudio = true)
        val audio = FakeNativeAudio().apply {
            started = true
            nextNoteId = null
        }
        val scheduler = NativeAudioScheduler(callbacks, audio)

        scheduler.schedule(
            scoreOf(note(start = 0.0, end = 0.10, startTick = 0, endTick = 10)),
            playheadSeconds = 0.0,
            speed = 1.0,
            playing = true
        )
        scheduler.stop()

        assertEquals(0, audio.allSoundOffCalls)
        assertEquals(emptyList<Int>(), audio.noteOffs)
    }

    @Test
    fun stopImmediatelyReleasesOnlyScheduledVoices() {
        val callbacks = FakeCallbacks(canUseNativeAudio = true)
        val audio = FakeNativeAudio().apply {
            started = true
            nextNoteId = 31
        }
        val scheduler = NativeAudioScheduler(callbacks, audio)
        val score = scoreOf(note(start = 0.0, end = 1.0, startTick = 0, endTick = 100))

        scheduler.reset(score, playheadSeconds = 0.0)
        scheduler.schedule(score, playheadSeconds = 0.0, speed = 1.0, playing = true)
        scheduler.stop()

        assertEquals(listOf(31), audio.noteOffs)
        assertEquals(0, audio.allSoundOffCalls)
    }

    @Test
    fun resetReleasesPreviouslyScheduledVoicesBeforeMovingCursor() {
        val callbacks = FakeCallbacks(canUseNativeAudio = true)
        val audio = FakeNativeAudio().apply {
            started = true
            nextNoteId = 41
        }
        val scheduler = NativeAudioScheduler(callbacks, audio)
        val score = scoreOf(note(start = 0.0, end = 1.0, startTick = 0, endTick = 100))

        scheduler.reset(score, playheadSeconds = 0.0)
        scheduler.schedule(score, playheadSeconds = 0.0, speed = 1.0, playing = true)
        scheduler.reset(score, playheadSeconds = 0.5)

        assertEquals(listOf(41), audio.noteOffs)
        assertEquals(0, audio.allSoundOffCalls)
    }

    private class FakeCallbacks : NativeAudioScheduler.Callbacks {
        constructor(canUseNativeAudio: Boolean = false) {
            this.canUseNativeAudio = canUseNativeAudio
        }

        private val canUseNativeAudio: Boolean
        var lastReady: Boolean? = null

        override fun canUseNativeAudio(): Boolean = canUseNativeAudio

        override fun onAudioReadyChanged(ready: Boolean) {
            lastReady = ready
        }
    }

    private companion object {
        fun scoreOf(vararg notes: WaterfallNote): ParsedScore {
            return ParsedScore(
                title = "test",
                format = "test",
                ticksPerQuarter = 480,
                tempos = emptyList(),
                meters = emptyList(),
                tempoMap = emptyList(),
                rawEvents = emptyList(),
                notes = notes.toList(),
                longNotes = emptyList(),
                duration = notes.maxOfOrNull { it.end } ?: 0.0
            )
        }

        fun note(
            start: Double,
            end: Double,
            startTick: Long,
            endTick: Long,
            pitch: Double = 60.0
        ): WaterfallNote {
            return WaterfallNote(
                startTick = startTick,
                endTick = endTick,
                start = start,
                end = end,
                pitch = pitch,
                midiPitch = pitch.toInt(),
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
}
