package icu.ringona.xensynth.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScaleGuideTest {
    @Test
    fun usesNewLengthMark() {
        val guide = guide(
            marks = mapOf('4' to 0.2f),
            scales = mapOf(4 to "04N4")
        )

        val tick = guide.tickForPitch(4, 63.0, 100f)

        assertTrue(tick.isVisible)
        assertEquals(16.8f, tick.length, 0.0001f)
    }

    @Test
    fun promotedEdoStepsUseSecondLevelMarks() {
        val guide = guide(
            marks = mapOf('2' to 0.6f),
            scales = mapOf(
                27 to "033332333322333313333233332",
                29 to "03333233332323333133332333323"
            )
        )

        val step21Tick = guide.tickForPitch(27, 21 * 12.0 / 27.0, 100f)
        val step22Tick = guide.tickForPitch(29, 22 * 12.0 / 29.0, 100f)

        assertTrue(step21Tick.isVisible)
        assertTrue(step22Tick.isVisible)
        assertEquals(50.4f, step21Tick.length, 0.0001f)
        assertEquals(50.4f, step22Tick.length, 0.0001f)
    }

    @Test
    fun sMarkerHidesTickAndSplitsTouchSpace() {
        val guide = guide(scales = mapOf(4 to "0S0N"))

        assertFalse(guide.tickForPitch(4, 63.0, 100f).isVisible)
        assertEquals(60.0, guide.touchPitchForRaw(4, 62.9) ?: -1.0, 0.0001)
        assertEquals(66.0, guide.touchPitchForRaw(4, 63.1) ?: -1.0, 0.0001)
    }

    @Test
    fun continuousSMarkersExtendMutedTouchSpace() {
        val guide = guide(scales = mapOf(4 to "0SS0"))

        assertEquals(60.0, guide.touchPitchForRaw(4, 64.4) ?: -1.0, 0.0001)
        assertEquals(69.0, guide.touchPitchForRaw(4, 64.6) ?: -1.0, 0.0001)
    }

    @Test
    fun nMarkerStillAllowsTouchPitch() {
        val guide = guide(scales = mapOf(4 to "0N0N"))

        assertFalse(guide.tickForPitch(4, 63.0, 100f).isVisible)
        assertEquals(63.0, guide.touchPitchForRaw(4, 63.0) ?: -1.0, 0.0001)
    }

    @Test
    fun customProfileTruncatesAndDrawsCentsMarks() {
        val guide = ScaleGuide.fromCustomProfile(
            profileName = "longname9",
            marks = mapOf(203.91 to 0.8f)
        )

        val lines = guide.linesForVisibleRange(12, 60.0, 63.0)
        val cLine = lines.first { it.isC && kotlin.math.abs(it.pitch - 60.0) < 0.0001 }
        val customLine = lines.first { kotlin.math.abs(it.pitch - 62.0391) < 0.0001 }

        assertTrue(guide.isCustom)
        assertEquals("longname", guide.profileName)
        assertEquals(1f, cLine.ratio, 0.0001f)
        assertEquals(0.8f, customLine.ratio, 0.0001f)
        assertEquals(0.8f, customLine.strokeRatio ?: -1f, 0.0001f)
    }

    @Test
    fun denseScaleLinesAreThinnedButKeepCMarks() {
        val guide = guide(scales = mapOf(12 to "000000000000"))

        val dense = guide.linesForVisibleRange(12, 60.0, 72.0)
        val sparse = guide.linesForVisibleRange(12, 60.0, 72.0, minPitchSpacing = 2.5)

        assertTrue(sparse.size < dense.size)
        assertTrue(sparse.any { it.isC && kotlin.math.abs(it.pitch - 60.0) < 0.0001 })
        assertTrue(sparse.any { it.isC && kotlin.math.abs(it.pitch - 72.0) < 0.0001 })
        assertFalse(sparse.any { kotlin.math.abs(it.pitch - 61.0) < 0.0001 })
    }

    @Test
    fun denseScaleLinesFadeBetweenStrideThresholds() {
        val guide = guide(scales = mapOf(12 to "000000000000"))

        val lines = guide.linesForVisibleRange(12, 60.0, 64.0, minPitchSpacing = 2.5)
        val lowerStrideLine = lines.first { kotlin.math.abs(it.pitch - 62.0) < 0.0001 }
        val upperStrideLine = lines.first { kotlin.math.abs(it.pitch - 63.0) < 0.0001 }

        assertEquals(0.5f, lowerStrideLine.ratio, 0.0001f)
        assertEquals(0.5f, upperStrideLine.ratio, 0.0001f)
        assertFalse(lines.any { kotlin.math.abs(it.pitch - 61.0) < 0.0001 })
    }

    @Test
    fun denseCustomLinesAreThinnedButKeepCMarks() {
        val guide = ScaleGuide.fromCustomProfile(
            profileName = "dense",
            marks = mapOf(100.0 to 1f, 200.0 to 1f, 300.0 to 1f)
        )

        val dense = guide.linesForVisibleRange(12, 60.0, 64.0)
        val sparse = guide.linesForVisibleRange(12, 60.0, 64.0, minPitchSpacing = 1.5)

        assertTrue(sparse.size < dense.size)
        assertTrue(sparse.any { it.isC && kotlin.math.abs(it.pitch - 60.0) < 0.0001 })
        assertFalse(sparse.any { kotlin.math.abs(it.pitch - 61.0) < 0.0001 })
    }

    @Test
    fun customTouchPitchUsesConfiguredCents() {
        val guide = ScaleGuide.fromCustomProfile(
            profileName = "8afdo",
            marks = mapOf(203.91 to 0.8f, 701.955 to 0.8f)
        )

        assertEquals(62.0391, guide.touchPitchForRaw(0, 62.1) ?: -1.0, 0.0001)
        assertEquals(67.01955, guide.touchPitchForRaw(12, 67.1) ?: -1.0, 0.0001)
    }

    @Test
    fun customProfileAppliesKeybindAgainstOctaveC() {
        val guide = ScaleGuide.fromCustomProfile(
            profileName = "8afdo",
            marks = mapOf(203.91 to 0.8f),
            keybind = mapOf(
                1 to 0.0,
                2 to 203.91,
                11 to 1088.27
            )
        )

        assertTrue(guide.hasKeybind)
        assertEquals(60.0, guide.playbackPitchForMidiPitch(60), 0.0001)
        assertEquals(60.0, guide.playbackPitchForMidiPitch(61), 0.0001)
        assertEquals(62.0391, guide.playbackPitchForMidiPitch(62), 0.0001)
        assertEquals(70.8827, guide.playbackPitchForMidiPitch(71), 0.0001)
        assertEquals(72.0, guide.playbackPitchForMidiPitch(72), 0.0001)
    }

    private fun guide(
        marks: Map<Char, Float> = emptyMap(),
        scales: Map<Int, String>
    ): ScaleGuide {
        val finalMarks = mutableMapOf('0' to 1f)
        finalMarks.putAll(marks)
        finalMarks['N'] = 0f
        finalMarks['S'] = 0f
        return ScaleGuide(finalMarks, scales)
    }
}
