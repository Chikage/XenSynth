package icu.ringona.xensynth.audio

import icu.ringona.xensynth.FakeNativeAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAudioControllerTest {
    @Test
    fun startRunsSetupAndStart() {
        val audio = FakeNativeAudio()
        val controller = NativeAudioController(audio)

        assertTrue(controller.start())

        assertTrue(controller.ready)
        assertEquals(1, audio.setupCalls)
        assertEquals(1, audio.startCalls)
    }

    @Test
    fun startFailureMarksControllerNotReady() {
        val audio = FakeNativeAudio().apply {
            startResult = false
        }
        val controller = NativeAudioController(audio)

        assertFalse(controller.start())

        assertFalse(controller.ready)
    }

    @Test
    fun recoverSkipsRestartWhenAlreadyStarted() {
        val audio = FakeNativeAudio().apply {
            started = true
        }
        val controller = NativeAudioController(audio)

        assertTrue(controller.recoverStartedStream())

        assertEquals(0, audio.restartCalls)
    }

    @Test
    fun recoverRestartsWhenStateUnavailable() {
        val audio = FakeNativeAudio().apply {
            throwOnIsStarted = true
            restartResult = true
        }
        val controller = NativeAudioController(audio)

        assertTrue(controller.recoverStartedStream())

        assertEquals(1, audio.restartCalls)
    }

    @Test
    fun setGainAndTeardownAreContained() {
        val audio = FakeNativeAudio()
        val controller = NativeAudioController(audio)

        assertTrue(controller.setGain(1.25f))
        controller.teardown()

        assertEquals(1, audio.setGainCalls)
        assertEquals(1.25f, audio.lastGain, 0.0001f)
        assertEquals(1, audio.teardownCalls)
        assertFalse(controller.ready)
    }

    @Test
    fun noteOnAndOffAreContained() {
        val audio = FakeNativeAudio().apply {
            nextNoteId = 42
        }
        val controller = NativeAudioController(audio)

        val noteId = controller.noteOn(key = 60, velocity = 96, channel = 2)
        val noteOffSuccess = controller.noteOff(42)

        assertEquals(42, noteId)
        assertTrue(noteOffSuccess)
        assertEquals(1, audio.noteOns.size)
        assertEquals(60, audio.noteOns.single().key)
        assertEquals(96, audio.noteOns.single().velocity)
        assertEquals(2, audio.noteOns.single().channel)
        assertEquals(listOf(42), audio.noteOffs)
    }
}
