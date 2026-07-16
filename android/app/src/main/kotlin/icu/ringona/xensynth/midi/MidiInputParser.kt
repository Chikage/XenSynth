package icu.ringona.xensynth.midi

sealed class MidiInputEvent {
    data class NoteOn(
        val pitch: Int,
        val velocity: Int,
        val channel: Int
    ) : MidiInputEvent()

    data class NoteOff(
        val pitch: Int,
        val channel: Int
    ) : MidiInputEvent()

    data class SustainPedal(
        val down: Boolean,
        val channel: Int
    ) : MidiInputEvent()

    data class ProgramChange(
        val program: Int,
        val channel: Int
    ) : MidiInputEvent()

    data class AllNotesOff(
        val channel: Int
    ) : MidiInputEvent()
}

class MidiInputParser(
    private val onEvent: (MidiInputEvent) -> Unit
) {
    private var runningStatus = 0
    private var pendingStatus = 0
    private var pendingDataCount = 0
    private var expectedDataCount = 0
    private val pendingData = IntArray(2)

    fun send(message: ByteArray, offset: Int = 0, count: Int = message.size) {
        require(offset >= 0 && count >= 0 && offset + count <= message.size) {
            "Invalid MIDI message range"
        }
        for (index in offset until offset + count) {
            parseByte(message[index].toInt() and 0xFF)
        }
    }

    fun reset() {
        runningStatus = 0
        pendingStatus = 0
        pendingDataCount = 0
        expectedDataCount = 0
    }

    private fun parseByte(value: Int) {
        if ((value and STATUS_BIT) != 0) {
            handleStatusByte(value)
            return
        }
        if (expectedDataCount == 0 && runningStatus != 0) {
            pendingStatus = runningStatus
            expectedDataCount = channelMessageDataCount(pendingStatus)
            pendingDataCount = 0
        }
        if (expectedDataCount == 0) {
            return
        }
        pendingData[pendingDataCount++] = value and DATA_MASK
        if (pendingDataCount == expectedDataCount) {
            dispatchChannelMessage(pendingStatus, pendingData[0], pendingData[1])
            pendingDataCount = 0
            if (runningStatus == 0) {
                pendingStatus = 0
                expectedDataCount = 0
            }
        }
    }

    private fun handleStatusByte(status: Int) {
        if (status >= REALTIME_STATUS_START) {
            return
        }
        if (status >= SYSTEM_STATUS_START) {
            reset()
            return
        }
        val dataCount = channelMessageDataCount(status)
        runningStatus = status
        pendingStatus = status
        pendingDataCount = 0
        expectedDataCount = dataCount
    }

    private fun dispatchChannelMessage(status: Int, data1: Int, data2: Int) {
        val channel = status and CHANNEL_MASK
        when (status and MESSAGE_TYPE_MASK) {
            NOTE_OFF -> onEvent(
                MidiInputEvent.NoteOff(
                    pitch = data1.coerceIn(MIDI_VALUE_MIN, MIDI_VALUE_MAX),
                    channel = channel
                )
            )
            NOTE_ON -> {
                val pitch = data1.coerceIn(MIDI_VALUE_MIN, MIDI_VALUE_MAX)
                val velocity = data2.coerceIn(MIDI_VALUE_MIN, MIDI_VALUE_MAX)
                if (velocity == 0) {
                    onEvent(MidiInputEvent.NoteOff(pitch = pitch, channel = channel))
                } else {
                    onEvent(
                        MidiInputEvent.NoteOn(
                            pitch = pitch,
                            velocity = velocity,
                            channel = channel
                        )
                    )
                }
            }
            CONTROL_CHANGE -> {
                when (data1) {
                    SUSTAIN_PEDAL_CONTROLLER -> {
                        onEvent(
                            MidiInputEvent.SustainPedal(
                                down = data2 >= SUSTAIN_PEDAL_DOWN_VALUE,
                                channel = channel
                            )
                        )
                    }
                    ALL_SOUND_OFF_CONTROLLER,
                    ALL_NOTES_OFF_CONTROLLER -> onEvent(MidiInputEvent.AllNotesOff(channel))
                }
            }
            PROGRAM_CHANGE -> onEvent(
                MidiInputEvent.ProgramChange(
                    program = data1.coerceIn(MIDI_VALUE_MIN, MIDI_VALUE_MAX),
                    channel = channel
                )
            )
        }
    }

    private fun channelMessageDataCount(status: Int): Int {
        return when (status and MESSAGE_TYPE_MASK) {
            PROGRAM_CHANGE, CHANNEL_PRESSURE -> 1
            NOTE_OFF, NOTE_ON, POLY_PRESSURE, CONTROL_CHANGE, PITCH_BEND -> 2
            else -> 0
        }
    }

    companion object {
        private const val STATUS_BIT = 0x80
        private const val DATA_MASK = 0x7F
        private const val CHANNEL_MASK = 0x0F
        private const val MESSAGE_TYPE_MASK = 0xF0
        private const val SYSTEM_STATUS_START = 0xF0
        private const val REALTIME_STATUS_START = 0xF8
        private const val MIDI_VALUE_MIN = 0
        private const val MIDI_VALUE_MAX = 127

        private const val NOTE_OFF = 0x80
        private const val NOTE_ON = 0x90
        private const val POLY_PRESSURE = 0xA0
        private const val CONTROL_CHANGE = 0xB0
        private const val PROGRAM_CHANGE = 0xC0
        private const val CHANNEL_PRESSURE = 0xD0
        private const val PITCH_BEND = 0xE0

        private const val SUSTAIN_PEDAL_CONTROLLER = 64
        private const val SUSTAIN_PEDAL_DOWN_VALUE = 64
        private const val ALL_SOUND_OFF_CONTROLLER = 120
        private const val ALL_NOTES_OFF_CONTROLLER = 123
    }
}
