package icu.ringona.rtpmidi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RtpMidiRecoveryJournalTest {
    @Test
    fun chapterCAndNGoldenVectorUsesRfc6295BitLayout() {
        val journal = RtpMidiRecoveryJournal(
            checkpointSequenceNumber = 0x1234,
            singlePacketLoss = false,
            channels = listOf(
                RtpMidiChannelRecoveryJournal(
                    channel = 0,
                    singlePacketLoss = false,
                    controlLogs = listOf(
                        RtpMidiControlJournalLog(
                            controller = 64,
                            tool = RtpMidiControlRecoveryTool.TOGGLE,
                            value = 1,
                            singlePacketLoss = false,
                        ),
                    ),
                    noteLogs = listOf(
                        RtpMidiNoteJournalLog(
                            note = 60,
                            velocity = 100,
                            playOnRecovery = false,
                            singlePacketLoss = true,
                        ),
                    ),
                    noteOffs = setOf(64),
                    noteOffSinglePacketLoss = false,
                ),
            ),
        )

        val encoded = RtpMidiRecoveryJournalCodec.encode(journal)

        assertArrayEquals(hex("20 12 34 00 0B 48 00 40 81 01 88 BC 64 80"), encoded)
        assertEquals(journal, RtpMidiRecoveryJournalCodec.decode(encoded))
    }

    @Test
    fun lostNoteOffIsRecoveredFromChapterN() {
        val sender = RtpMidiRecoveryJournalSender()
        val receiver = RtpMidiRecoveryJournalReceiver()
        val noteOn = MidiChannelMessage(0x90, 60, 100)
        val noteOff = MidiChannelMessage(0x80, 60, 0)
        sender.recordPacket(10, listOf(noteOn), sentAtNanos = 0L)
        receiver.observe(listOf(noteOn))
        sender.recordPacket(11, listOf(noteOff), sentAtNanos = 1L)
        val journal = sender.journalForPacket(12)

        val recovery = receiver.recover(
            journalBytes = journal,
            missingExtendedSequence = 11,
            journalPacketExtendedSequence = 12,
        )

        assertTrue(recovery.journalApplied)
        assertEquals(1, recovery.messages.size)
        assertArrayEquals(byteArrayOf(0x80.toByte(), 60, 0), recovery.messages.single())
    }

    @Test
    fun lostSustainReleaseAndAllSoundOffUseChapterCToggleAndCountTools() {
        val sender = RtpMidiRecoveryJournalSender()
        val receiver = RtpMidiRecoveryJournalReceiver()
        val noteOn = MidiChannelMessage(0x90, 60, 100)
        val sustainOn = MidiChannelMessage(0xB0, 64, 127)
        sender.recordPacket(30, listOf(noteOn, sustainOn), sentAtNanos = 0L)
        receiver.observe(listOf(noteOn, sustainOn))

        sender.recordPacket(
            31,
            listOf(MidiChannelMessage(0xB0, 64, 0), MidiChannelMessage(0xB0, 120, 0)),
            sentAtNanos = 1L,
        )
        val recovery = receiver.recover(
            journalBytes = sender.journalForPacket(32),
            missingExtendedSequence = 31,
            journalPacketExtendedSequence = 32,
        )

        assertTrue(recovery.journalApplied)
        assertTrue(recovery.messages.any { it.contentEquals(byteArrayOf(0xB0.toByte(), 64, 0)) })
        assertTrue(recovery.messages.any { it.contentEquals(byteArrayOf(0xB0.toByte(), 120, 0)) })
    }

    @Test
    fun twoMissingSustainTogglesReplayReleaseBeforeReturningPedalDown() {
        val sender = RtpMidiRecoveryJournalSender()
        val receiver = RtpMidiRecoveryJournalReceiver()
        val sustainOn = MidiChannelMessage(0xB0, 64, 127)
        sender.recordPacket(40, listOf(sustainOn), sentAtNanos = 0L)
        receiver.observe(listOf(sustainOn))

        sender.recordPacket(
            41,
            listOf(MidiChannelMessage(0xB0, 64, 0), MidiChannelMessage(0xB0, 64, 127)),
            sentAtNanos = 1L,
        )
        val recovery = receiver.recover(
            journalBytes = sender.journalForPacket(42),
            missingExtendedSequence = 41,
            journalPacketExtendedSequence = 42,
        )

        assertEquals(2, recovery.messages.size)
        assertArrayEquals(byteArrayOf(0xB0.toByte(), 64, 0), recovery.messages[0])
        assertArrayEquals(byteArrayOf(0xB0.toByte(), 64, 127), recovery.messages[1])
    }

    @Test
    fun fallbackReleaseDoesNotReplayPedalDownForLaterLostSustainRelease() {
        val sender = RtpMidiRecoveryJournalSender()
        val receiver = RtpMidiRecoveryJournalReceiver()
        val sustainOn = MidiChannelMessage(0xB0, 64, 127)
        sender.recordPacket(45, listOf(sustainOn), sentAtNanos = 0L)
        receiver.observe(listOf(sustainOn))
        sender.acknowledge(45)

        val fallback = receiver.recover(
            journalBytes = null,
            missingExtendedSequence = 45,
            journalPacketExtendedSequence = 46,
        )
        assertArrayEquals(byteArrayOf(0xB0.toByte(), 64, 0), fallback.messages.single())

        sender.recordPacket(46, listOf(MidiChannelMessage(0xB0, 64, 0)), sentAtNanos = 1L)
        val recovery = receiver.recover(
            journalBytes = sender.journalForPacket(47),
            missingExtendedSequence = 46,
            journalPacketExtendedSequence = 47,
        )

        assertTrue(recovery.journalApplied)
        assertTrue(recovery.messages.isEmpty())
    }

    @Test
    fun forcedReleaseKeepsResetAllControllersSustainToggleBaseline() {
        val sender = RtpMidiRecoveryJournalSender()
        val receiver = RtpMidiRecoveryJournalReceiver()
        val sustainOn = MidiChannelMessage(0xB0, 64, 127)
        sender.recordPacket(48, listOf(sustainOn), sentAtNanos = 0L)
        receiver.observe(listOf(sustainOn))
        sender.acknowledge(48)
        receiver.forceReleasedState()

        sender.recordPacket(49, listOf(MidiChannelMessage(0xB0, 121, 0)), sentAtNanos = 1L)
        val resetRecovery = receiver.recover(
            journalBytes = sender.journalForPacket(50),
            missingExtendedSequence = 49,
            journalPacketExtendedSequence = 50,
        )
        assertEquals(1, resetRecovery.messages.size)
        assertArrayEquals(byteArrayOf(0xB0.toByte(), 121, 0), resetRecovery.messages.single())

        sender.acknowledge(49)
        sender.recordPacket(50, listOf(sustainOn), sentAtNanos = 2L)
        val sustainRecovery = receiver.recover(
            journalBytes = sender.journalForPacket(51),
            missingExtendedSequence = 50,
            journalPacketExtendedSequence = 51,
        )
        assertEquals(1, sustainRecovery.messages.size)
        assertArrayEquals(byteArrayOf(0xB0.toByte(), 64, 127), sustainRecovery.messages.single())
    }

    @Test
    fun lostResetAllControllersUsesCountToolAndPreservesActiveNotes() {
        val sender = RtpMidiRecoveryJournalSender()
        val receiver = RtpMidiRecoveryJournalReceiver()
        val noteOn = MidiChannelMessage(0x90, 60, 100)
        val sustainOn = MidiChannelMessage(0xB0, 64, 127)
        sender.recordPacket(50, listOf(noteOn, sustainOn), sentAtNanos = 0L)
        receiver.observe(listOf(noteOn, sustainOn))
        sender.acknowledge(50)

        sender.recordPacket(51, listOf(MidiChannelMessage(0xB0, 121, 0)), sentAtNanos = 1L)
        val journal = sender.journalForPacket(52)
        val controlLog = RtpMidiRecoveryJournalCodec.decode(journal!!)
            .channels.single().controlLogs.single()
        assertEquals(121, controlLog.controller)
        assertEquals(RtpMidiControlRecoveryTool.COUNT, controlLog.tool)
        assertEquals(1, controlLog.value)

        val recovery = receiver.recover(
            journalBytes = journal,
            missingExtendedSequence = 51,
            journalPacketExtendedSequence = 52,
        )

        assertEquals(1, recovery.messages.size)
        assertArrayEquals(byteArrayOf(0xB0.toByte(), 121, 0), recovery.messages.single())

        sender.acknowledge(51)
        sender.recordPacket(52, listOf(sustainOn), sentAtNanos = 2L)
        val sustainRecovery = receiver.recover(
            journalBytes = sender.journalForPacket(53),
            missingExtendedSequence = 52,
            journalPacketExtendedSequence = 53,
        )
        assertEquals(1, sustainRecovery.messages.size)
        assertArrayEquals(byteArrayOf(0xB0.toByte(), 64, 127), sustainRecovery.messages.single())

        val fallback = receiver.recover(
            journalBytes = null,
            missingExtendedSequence = 53,
            journalPacketExtendedSequence = 54,
        )
        assertTrue(fallback.messages.any { it.contentEquals(byteArrayOf(0x80.toByte(), 60, 0)) })
    }

    @Test
    fun resetAllControllersCountWrapsModuloSixtyFour() {
        val sender = RtpMidiRecoveryJournalSender()
        val receiver = RtpMidiRecoveryJournalReceiver()
        val resetMessages = List(65) { MidiChannelMessage(0xB0, 121, 0) }
        sender.recordPacket(
            packetExtendedSequence = 60,
            messages = resetMessages,
            sentAtNanos = 0L,
        )
        receiver.observe(resetMessages)

        val journal = sender.journalForPacket(61)!!
        val controlLog = RtpMidiRecoveryJournalCodec.decode(journal)
            .channels.single().controlLogs.single()

        assertEquals(121, controlLog.controller)
        assertEquals(RtpMidiControlRecoveryTool.COUNT, controlLog.tool)
        assertEquals(1, controlLog.value)

        val recovery = receiver.recover(
            journalBytes = journal,
            missingExtendedSequence = 60,
            journalPacketExtendedSequence = 61,
        )
        assertTrue(recovery.journalApplied)
        assertTrue(recovery.messages.isEmpty())
    }

    @Test
    fun channelModeControllersHaveIndependentModuloCounts() {
        val sender = RtpMidiRecoveryJournalSender()
        val receiver = RtpMidiRecoveryJournalReceiver()
        val messages = buildList {
            for (controller in 124..127) {
                repeat(65) {
                    add(MidiChannelMessage(0xB0, controller, 0))
                }
            }
        }
        sender.recordPacket(70, messages, sentAtNanos = 0L)
        receiver.observe(messages)

        val journal = sender.journalForPacket(71)!!
        val controlLogs = RtpMidiRecoveryJournalCodec.decode(journal)
            .channels.single().controlLogs.associateBy { it.controller }

        assertEquals(setOf(124, 125, 126, 127), controlLogs.keys)
        for (controller in 124..127) {
            assertEquals(RtpMidiControlRecoveryTool.COUNT, controlLogs.getValue(controller).tool)
            assertEquals(1, controlLogs.getValue(controller).value)
        }

        val recovery = receiver.recover(
            journalBytes = journal,
            missingExtendedSequence = 70,
            journalPacketExtendedSequence = 71,
        )
        assertTrue(recovery.journalApplied)
        assertTrue(recovery.messages.isEmpty())
    }

    @Test
    fun lostChannelModeControllersAreRecoveredAndClearActiveNotes() {
        val sender = RtpMidiRecoveryJournalSender()
        val receiver = RtpMidiRecoveryJournalReceiver()
        val noteOn = MidiChannelMessage(0x90, 60, 100)
        sender.recordPacket(80, listOf(noteOn), sentAtNanos = 0L)
        receiver.observe(listOf(noteOn))
        sender.acknowledge(80)

        val channelModeMessages = (124..127).map { MidiChannelMessage(0xB0, it, 0) }
        sender.recordPacket(81, channelModeMessages, sentAtNanos = 1L)
        val recovery = receiver.recover(
            journalBytes = sender.journalForPacket(82),
            missingExtendedSequence = 81,
            journalPacketExtendedSequence = 82,
        )

        assertEquals(4, recovery.messages.size)
        for (controller in 124..127) {
            assertArrayEquals(
                byteArrayOf(0xB0.toByte(), controller.toByte(), 0),
                recovery.messages[controller - 124],
            )
        }

        val fallback = receiver.recover(
            journalBytes = null,
            missingExtendedSequence = 82,
            journalPacketExtendedSequence = 83,
        )
        assertTrue(fallback.messages.isEmpty())
    }

    @Test
    fun journalContainsOnlyHistoryBeforeCurrentPacket() {
        val sender = RtpMidiRecoveryJournalSender()

        assertNull(sender.journalForPacket(100))
        sender.recordPacket(
            packetExtendedSequence = 100,
            messages = listOf(MidiChannelMessage(0x80, 60, 0)),
            sentAtNanos = 0L,
        )

        val nextJournal = sender.journalForPacket(101)
        assertNotNull(nextJournal)
        val decoded = RtpMidiRecoveryJournalCodec.decode(nextJournal!!)
        assertEquals(100, decoded.checkpointSequenceNumber)
        assertEquals(setOf(60), decoded.channels.single().noteOffs)
    }

    @Test
    fun receiverFeedbackPrunesAcrossSixteenBitWrap() {
        val sender = RtpMidiRecoveryJournalSender()
        sender.recordPacket(
            65_535,
            listOf(MidiChannelMessage(0x90, 60, 100)),
            sentAtNanos = 0L,
        )
        assertNotNull(sender.journalForPacket(65_536))
        sender.recordPacket(65_536, emptyList(), sentAtNanos = 1L)

        assertEquals(65_536L, sender.acknowledge(0))
        assertFalse(sender.hasUnacknowledgedState)
        assertNull(sender.journalForPacket(65_537))

        // A reordered pre-wrap feedback packet must not move the acknowledgement backwards.
        assertEquals(65_536L, sender.acknowledge(65_535))
    }

    @Test
    fun receiverFeedbackRacingPacketRecordingDoesNotLeaveStaleJournalState() {
        val sender = RtpMidiRecoveryJournalSender()
        sender.reservePacketSequence(42)

        assertEquals(42L, sender.acknowledge(42))
        sender.recordPacket(
            42,
            listOf(MidiChannelMessage(0x80, 60, 0)),
            sentAtNanos = 1L,
        )

        assertFalse(sender.hasUnacknowledgedState)
        assertNull(sender.journalForPacket(43))
    }

    @Test
    fun unacknowledgedCriticalReleaseAllowsShorterHeartbeatSelection() {
        val sender = RtpMidiRecoveryJournalSender()
        sender.recordPacket(
            7,
            listOf(MidiChannelMessage(0x80, 60, 0)),
            sentAtNanos = 1_000L,
        )

        assertTrue(sender.hasUnacknowledgedCriticalRelease)
        val intervalNanos = if (sender.hasUnacknowledgedCriticalRelease) {
            35_000_000L
        } else {
            100_000_000L
        }
        assertFalse(sender.heartbeatDue(35_000_999L, intervalNanos))
        assertTrue(sender.heartbeatDue(35_001_000L, intervalNanos))
        sender.acknowledge(7)
        assertFalse(sender.hasUnacknowledgedCriticalRelease)
        assertFalse(sender.heartbeatDue(Long.MAX_VALUE, intervalNanos))
    }

    @Test
    fun activeNoteAndSustainPressRemainNormalUnacknowledgedState() {
        val sender = RtpMidiRecoveryJournalSender()
        sender.recordPacket(
            8,
            listOf(
                MidiChannelMessage(0x90, 60, 100),
                MidiChannelMessage(0xB0, 64, 127),
            ),
            sentAtNanos = 1_000L,
        )

        assertTrue(sender.hasUnacknowledgedState)
        assertFalse(sender.hasUnacknowledgedCriticalRelease)
        assertFalse(sender.heartbeatDue(100_000_999L, 100_000_000L))
        assertTrue(sender.heartbeatDue(100_001_000L, 100_000_000L))
    }

    @Test
    fun releaseAndResetControllersAreCriticalUntilAcknowledged() {
        val controllers = listOf(64, 120, 121, 123, 124, 125, 126, 127)

        controllers.forEachIndexed { index, controller ->
            val sender = RtpMidiRecoveryJournalSender()
            val sequence = 20L + index
            sender.recordPacket(
                sequence,
                listOf(MidiChannelMessage(0xB0, controller, 0)),
                sentAtNanos = 1_000L,
            )

            assertTrue(
                "CC$controller should be a critical release",
                sender.hasUnacknowledgedCriticalRelease,
            )
            sender.acknowledge((sequence and 0xFFFF).toInt())
            assertFalse(sender.hasUnacknowledgedCriticalRelease)
        }
    }

    @Test
    fun newerActiveStateSupersedesUnacknowledgedNoteAndSustainRelease() {
        val sender = RtpMidiRecoveryJournalSender()
        sender.recordPacket(
            40,
            listOf(
                MidiChannelMessage(0x80, 60, 0),
                MidiChannelMessage(0xB0, 64, 0),
            ),
            sentAtNanos = 1_000L,
        )
        assertTrue(sender.hasUnacknowledgedCriticalRelease)

        sender.recordPacket(
            41,
            listOf(
                MidiChannelMessage(0x90, 60, 100),
                MidiChannelMessage(0xB0, 64, 127),
            ),
            sentAtNanos = 2_000L,
        )

        assertTrue(sender.hasUnacknowledgedState)
        assertFalse(sender.hasUnacknowledgedCriticalRelease)
    }

    @Test
    fun uncoveredOrUnsupportedJournalFallsBackToSafeRelease() {
        val receiver = RtpMidiRecoveryJournalReceiver()
        receiver.observe(
            listOf(
                MidiChannelMessage(0x90, 60, 100),
                MidiChannelMessage(0xB0, 64, 127),
            ),
        )
        val journal = RtpMidiRecoveryJournalCodec.encode(
            RtpMidiRecoveryJournal(
                checkpointSequenceNumber = 20,
                singlePacketLoss = true,
                channels = listOf(
                    RtpMidiChannelRecoveryJournal(
                        channel = 0,
                        singlePacketLoss = true,
                        noteOffs = setOf(60),
                    ),
                ),
            ),
        )

        val recovery = receiver.recover(
            journalBytes = journal,
            missingExtendedSequence = 19,
            journalPacketExtendedSequence = 21,
        )

        assertFalse(recovery.journalApplied)
        assertTrue(recovery.messages.any { it.contentEquals(byteArrayOf(0x80.toByte(), 60, 0)) })
        assertTrue(recovery.messages.any { it.contentEquals(byteArrayOf(0xB0.toByte(), 64, 0)) })
    }

    private fun hex(value: String): ByteArray = value
        .trim()
        .split(Regex("\\s+"))
        .map { it.toInt(16).toByte() }
        .toByteArray()
}
