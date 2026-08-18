package icu.ringona.xensynth.platform

import icu.ringona.rtpmidi.AppleMidiEvent
import icu.ringona.xensynth.audio.NativeAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkMidiAudioSchedulerTest {
    @Test
    fun noteOnUsesMappedPitchAndPreservesAbsoluteTarget() {
        val audio = RecordingNativeAudio()
        val scheduler = scheduler(audio)
        val pitches = DoubleArray(128) { it.toDouble() }.also { it[60] = 60.25 }
        scheduler.configure(pitches, program = 41)

        assertTrue(scheduler.onMidiEvent(event(0x90, 60, 100, target = 2_000_000_000L)))

        assertEquals(1, audio.starts.size)
        assertEquals(60, audio.starts.single().key)
        assertEquals(25f, audio.starts.single().cents, 0.0001f)
        assertEquals(41, audio.starts.single().program)
        assertEquals(2_000_000_000L, audio.starts.single().targetTimeNanos)
    }

    @Test
    fun noteOffKeepsItsTargetAndExpiredEventsRemainImmediateInNative() {
        val audio = RecordingNativeAudio()
        val scheduler = scheduler(audio)
        scheduler.onMidiEvent(event(0x90, 64, 90, target = 100L))

        assertTrue(scheduler.onMidiEvent(event(0x80, 64, 0, target = 200L)))

        assertEquals(Stop(1, 200L, false), audio.stops.single())
    }

    @Test
    fun sustainReleaseUsesPedalUpTarget() {
        val audio = RecordingNativeAudio()
        val scheduler = scheduler(audio)
        scheduler.onMidiEvent(event(0x92, 67, 90, target = 100L))
        scheduler.onMidiEvent(event(0xB2, 64, 127, target = 110L))
        scheduler.onMidiEvent(event(0x82, 67, 0, target = 120L))

        assertTrue(audio.stops.isEmpty())
        scheduler.onMidiEvent(event(0xB2, 64, 0, target = 180L))

        assertEquals(Stop(1, 180L, false), audio.stops.single())
    }

    @Test
    fun allNotesOffDoesNotDeleteANoteScheduledAfterIt() {
        val audio = RecordingNativeAudio()
        val scheduler = scheduler(audio)
        scheduler.onMidiEvent(event(0x90, 60, 90, target = 100L))
        scheduler.onMidiEvent(event(0xB0, 123, 0, target = 200L))
        scheduler.onMidiEvent(event(0x90, 62, 90, target = 201L))

        assertEquals(Stop(1, 200L, true), audio.stops.single())
        assertEquals(listOf(100L, 201L), audio.starts.map(Start::targetTimeNanos))
        assertTrue(scheduler.onMidiEvent(event(0x80, 62, 0, target = 300L)))
        assertEquals(Stop(2, 300L, false), audio.stops.last())
    }

    @Test
    fun allSoundOffHardStopsANoteWhoseNormalReleaseIsStillFinishing() {
        val audio = RecordingNativeAudio()
        val scheduler = scheduler(audio)
        scheduler.onMidiEvent(event(0x90, 60, 90, target = 100L))
        scheduler.onMidiEvent(event(0x80, 60, 0, target = 200L))

        scheduler.onMidiEvent(event(0xB0, 120, 0, target = 250L))

        assertEquals(
            listOf(
                Stop(1, 200L, false),
                Stop(1, 250L, true),
            ),
            audio.stops,
        )
    }

    @Test
    fun unsupportedMessagesRemainAvailableToTheOrdinaryInputPath() {
        val scheduler = scheduler(RecordingNativeAudio())

        assertFalse(scheduler.onMidiEvent(event(0xC0, 12, target = 100L)))
    }

    private fun event(
        status: Int,
        data1: Int,
        data2: Int? = null,
        target: Long,
    ) = AppleMidiEvent(
        bytes = if (data2 == null) {
            byteArrayOf(status.toByte(), data1.toByte())
        } else {
            byteArrayOf(status.toByte(), data1.toByte(), data2.toByte())
        },
        targetTimeNanos = target,
        sessionId = "session",
    )

    private fun scheduler(audio: RecordingNativeAudio) =
        NetworkMidiAudioScheduler(audio) { 0L }

    private data class Start(
        val key: Int,
        val cents: Float,
        val program: Int,
        val targetTimeNanos: Long,
    )

    private data class Stop(
        val noteId: Int,
        val targetTimeNanos: Long,
        val immediate: Boolean,
    )

    private class RecordingNativeAudio : NativeAudio {
        val starts = mutableListOf<Start>()
        val stops = mutableListOf<Stop>()
        private var nextId = 1

        override fun noteOnAt(
            key: Int,
            velocity: Int,
            cents: Float,
            channel: Int,
            program: Int,
            bankMsb: Int,
            bankLsb: Int,
            targetTimeNanos: Long,
            expression: Int,
        ): Int = nextId++.also {
            starts += Start(key, cents, program, targetTimeNanos)
        }

        override fun noteOffAt(noteId: Int, targetTimeNanos: Long, immediate: Boolean) {
            stops += Stop(noteId, targetTimeNanos, immediate)
        }

        override fun setup() = true
        override fun start() = true
        override fun teardown() = Unit
        override fun restart() = true
        override fun isStarted() = true
        override fun loadSf2(path: String) = true
        override fun loadBuiltinSf2() = true
        override fun unloadSf2() = Unit
        override fun hasSoundFont() = true
        override fun noteOn(
            key: Int,
            velocity: Int,
            cents: Float,
            channel: Int,
            program: Int,
            bankMsb: Int,
            bankLsb: Int,
            delaySeconds: Double,
            expression: Int,
        ): Int? = null
        override fun noteOff(noteId: Int) = Unit
        override fun allSoundOff() = Unit
        override fun setGain(gain: Float) = Unit
        override fun setReverb(value: Int) = Unit
        override fun setPitchCalibration(cents: FloatArray) = Unit
    }
}
