package icu.ringona.rtpmidi

import org.junit.Assert.assertEquals
import org.junit.Test

class RtpSequenceTrackerTest {
    @Test
    fun acceptsSmallReorderingOnce() {
        val tracker = RtpSequenceTracker()
        assertEquals(RtpSequenceDisposition.FIRST, tracker.observe(10).disposition)
        assertEquals(RtpSequenceDisposition.GAP, tracker.observe(12).disposition)
        assertEquals(RtpSequenceDisposition.LATE, tracker.observe(11).disposition)
        assertEquals(RtpSequenceDisposition.DUPLICATE, tracker.observe(11).disposition)
    }

    @Test
    fun handlesSixteenBitWraparound() {
        val tracker = RtpSequenceTracker()
        assertEquals(RtpSequenceDisposition.FIRST, tracker.observe(65_535).disposition)
        assertEquals(RtpSequenceDisposition.IN_ORDER, tracker.observe(0).disposition)
        assertEquals(RtpSequenceDisposition.IN_ORDER, tracker.observe(1).disposition)
        assertEquals(RtpSequenceDisposition.DUPLICATE, tracker.observe(65_535).disposition)
    }
}
