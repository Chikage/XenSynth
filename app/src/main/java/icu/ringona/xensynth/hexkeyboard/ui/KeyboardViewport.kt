package icu.ringona.xensynth.hexkeyboard.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.max

internal const val MIN_KEYBOARD_SCALE = 0.84f
internal const val MAX_KEYBOARD_SCALE = 3f
private const val KEYBOARD_FIT_TOLERANCE_PX = 0.5f

internal fun toolbarDragToKeyboardPan(drag: Offset): Offset = Offset(
    x = drag.x,
    y = if (drag.y == 0f) 0f else -drag.y,
)

internal fun constrainKeyboardPan(
    requestedPan: Offset,
    contentSize: Size,
    viewportSize: Size,
    edgeMargin: Float,
): Offset = Offset(
    x = constrainKeyboardPanAxis(
        requested = requestedPan.x,
        content = contentSize.width,
        viewport = viewportSize.width,
        edgeMargin = edgeMargin,
    ),
    y = constrainKeyboardPanAxis(
        requested = requestedPan.y,
        content = contentSize.height,
        viewport = viewportSize.height,
        edgeMargin = edgeMargin,
    ),
)

private fun constrainKeyboardPanAxis(
    requested: Float,
    content: Float,
    viewport: Float,
    edgeMargin: Float,
): Float {
    if (!requested.isFinite() || !content.isFinite() || !viewport.isFinite()) return 0f
    if (content <= viewport + KEYBOARD_FIT_TOLERANCE_PX) return 0f

    val overflowFromCenter = (content - viewport) / 2f
    val limit = overflowFromCenter + max(0f, edgeMargin)
    return requested.coerceIn(-limit, limit)
}


