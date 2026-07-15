package icu.ringona.xensynth.hexkeyboard.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardViewportTest {
    @Test
    fun `pan is disabled on an axis when the keyboard fully fits`() {
        val constrained = constrainKeyboardPan(
            requestedPan = Offset(120f, -90f),
            contentSize = Size(800f, 500f),
            viewportSize = Size(800f, 600f),
            edgeMargin = 24f,
        )

        assertEquals(Offset.Zero, constrained)
    }

    @Test
    fun `subpixel fit rounding does not accidentally enable pan`() {
        val constrained = constrainKeyboardPan(
            requestedPan = Offset(50f, 50f),
            contentSize = Size(800.4f, 600.4f),
            viewportSize = Size(800f, 600f),
            edgeMargin = 24f,
        )

        assertEquals(Offset.Zero, constrained)
    }

    @Test
    fun `pan is constrained independently for each overflowing axis`() {
        val constrained = constrainKeyboardPan(
            requestedPan = Offset(500f, -500f),
            contentSize = Size(1000f, 500f),
            viewportSize = Size(800f, 600f),
            edgeMargin = 24f,
        )

        assertEquals(Offset(124f, 0f), constrained)
    }

    @Test
    fun `edge margin allows a small reveal beyond the keyboard edge`() {
        val constrained = constrainKeyboardPan(
            requestedPan = Offset(-500f, 0f),
            contentSize = Size(1000f, 600f),
            viewportSize = Size(800f, 600f),
            edgeMargin = 20f,
        )

        assertEquals(Offset(-120f, 0f), constrained)
    }

    @Test
    fun `toolbar drags reveal the requested keyboard regions`() {
        assertEquals(
            Offset(-30f, 0f),
            toolbarDragToKeyboardPan(Offset(-30f, 0f)),
        )
        assertEquals(
            Offset(0f, -30f),
            toolbarDragToKeyboardPan(Offset(0f, 30f)),
        )
    }
}


