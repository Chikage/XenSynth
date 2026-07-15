package icu.ringona.xensynth.hexkeyboard.ui

import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import icu.ringona.xensynth.hexkeyboard.core.AxialCoordinate
import icu.ringona.xensynth.hexkeyboard.core.HexKey
import icu.ringona.xensynth.hexkeyboard.core.HexPoint
import icu.ringona.xensynth.hexkeyboard.core.HexaKeyboardLayout
import icu.ringona.xensynth.hexkeyboard.core.HexTouchHitTester
import icu.ringona.xensynth.hexkeyboard.core.PseudoPressureTracker
import icu.ringona.xensynth.hexkeyboard.core.TouchForce
import icu.ringona.xensynth.hexkeyboard.playback.KeyboardPlaybackNote
import icu.ringona.xensynth.hexkeyboard.playback.KeyboardPlaybackTimeline
import icu.ringona.xensynth.hexkeyboard.playback.PlaybackKeyVisual
import icu.ringona.xensynth.hexkeyboard.playback.PlaybackVisualFrame
import icu.ringona.xensynth.hexkeyboard.playback.visualFrameAt
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class KeyboardDisplayMode(val title: String) {
    Coordinates("Coordinates"),
    Pitch("Pitch"),
    Period("Period"),
}

private data class ModelBounds(
    val minX: Double,
    val maxX: Double,
    val minY: Double,
    val maxY: Double,
) {
    val width: Double get() = maxX - minX
    val height: Double get() = maxY - minY
}

private data class CanvasTransform(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val viewportPan: Offset,
) {
    fun point(point: HexPoint): Offset = Offset(
        x = point.x.toFloat() * scale + offsetX,
        y = point.y.toFloat() * scale + offsetY,
    )

    fun model(point: Offset): HexPoint = HexPoint(
        x = ((point.x - offsetX) / scale).toDouble(),
        y = ((point.y - offsetY) / scale).toDouble(),
    )
}

@Composable
fun HexKeyboardCanvas(
    layout: HexaKeyboardLayout,
    displayMode: KeyboardDisplayMode,
    selectedCoordinates: Set<AxialCoordinate>,
    selectionAnchorCoordinate: AxialCoordinate?,
    onKeyDown: (pointerId: Long, key: HexKey, velocity: Int, eventTimeMillis: Long) -> Unit,
    onKeyPressure: (pointerId: Long, expression: Int) -> Unit,
    onKeyUp: (pointerId: Long, eventTimeMillis: Long, retainForChord: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    keyboardScale: Float = 1f,
    keyboardPan: Offset = Offset.Zero,
    onKeyboardPanChange: (Offset) -> Unit = {},
    touchSensitivity: Float = 1.2f,
    pseudoPressureEnabled: Boolean = true,
    playbackTimeline: KeyboardPlaybackTimeline? = null,
    playbackPositionSeconds: Double = 0.0,
    activePlaybackNoteIndices: Set<Int> = emptySet(),
    touchEpoch: Long = 0L,
) {
    val latestOnKeyDown = rememberUpdatedState(onKeyDown)
    val latestOnKeyPressure = rememberUpdatedState(onKeyPressure)
    val latestOnKeyUp = rememberUpdatedState(onKeyUp)
    val latestOnKeyboardPanChange = rememberUpdatedState(onKeyboardPanChange)
    val activeCoordinates = remember(layout) { mutableStateMapOf<Long, AxialCoordinate>() }
    val activeForces = remember(layout) { mutableStateMapOf<Long, Float>() }
    val forceTrackers = remember(layout) { mutableMapOf<Long, PseudoPressureTracker>() }
    val lastExpressions = remember(layout) { mutableMapOf<Long, Int>() }
    val lastRawPressures = remember(layout) { mutableMapOf<Long, Float>() }

    DisposableEffect(layout, touchEpoch) {
        onDispose {
            val eventTimeMillis = SystemClock.uptimeMillis()
            activeCoordinates.keys.toList().forEach {
                latestOnKeyUp.value(it, eventTimeMillis, false)
            }
            activeCoordinates.clear()
            activeForces.clear()
            forceTrackers.clear()
            lastExpressions.clear()
            lastRawPressures.clear()
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val edgeMarginPx = with(density) { 24.dp.toPx() }
        val bounds = remember(layout) { modelBounds(layout) }
        val transform = remember(
            layout,
            widthPx,
            heightPx,
            edgeMarginPx,
            keyboardScale,
            keyboardPan,
        ) {
            canvasTransform(
                bounds = bounds,
                size = Size(widthPx, heightPx),
                scaleMultiplier = keyboardScale,
                requestedPan = keyboardPan,
                edgeMargin = edgeMarginPx,
            )
        }
        LaunchedEffect(keyboardPan, transform.viewportPan) {
            if (keyboardPan != transform.viewportPan) {
                latestOnKeyboardPanChange.value(transform.viewportPan)
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = "Hexagonal microtonal keyboard with ${layout.cells.size} keys"
                }
                .pointerInput(layout, transform, touchSensitivity, pseudoPressureEnabled, touchEpoch) {
                    try {
                        awaitPointerEventScope {
                            fun processSample(
                                pointerId: Long,
                                position: Offset,
                                uptimeMillis: Long,
                                pressed: Boolean,
                                rawPressure: Float,
                                hardwarePressureHint: Boolean,
                            ) {
                                val modelPoint = transform.model(position)
                                val previousCoordinate = activeCoordinates[pointerId]
                                val nextKey = if (pressed) {
                                    HexTouchHitTester.keyAt(
                                        point = modelPoint,
                                        layout = layout,
                                        previousCoordinate = previousCoordinate,
                                        sensitivity = touchSensitivity,
                                    )
                                } else {
                                    null
                                }
                                val nextCoordinate = nextKey?.coordinate
                                val force = if (nextKey != null && pseudoPressureEnabled) {
                                    forceTrackers.getOrPut(pointerId, ::PseudoPressureTracker).sample(
                                        rawPressure = rawPressure,
                                        uptimeMillis = uptimeMillis,
                                        point = modelPoint,
                                        keyCenter = nextKey.center,
                                        keyRadius = layout.configuration.radius.toDouble(),
                                        hardwarePressureHint = hardwarePressureHint,
                                    )
                                } else if (nextKey != null) {
                                    TouchForce.Fixed
                                } else {
                                    null
                                }

                                val keyChanged = previousCoordinate != nextCoordinate
                                if (keyChanged) {
                                    if (previousCoordinate != null) {
                                        activeCoordinates.remove(pointerId)
                                        activeForces.remove(pointerId)
                                        lastExpressions.remove(pointerId)
                                        latestOnKeyUp.value(pointerId, uptimeMillis, !pressed)
                                    }
                                    if (nextKey != null && force != null) {
                                        activeCoordinates[pointerId] = nextKey.coordinate
                                        latestOnKeyDown.value(
                                            pointerId,
                                            nextKey,
                                            force.velocity,
                                            uptimeMillis,
                                        )
                                    }
                                }

                                if (nextKey != null && force != null) {
                                    activeForces[pointerId] = force.normalized
                                    val previousExpression = lastExpressions[pointerId]
                                    if (
                                        keyChanged ||
                                        previousExpression == null ||
                                        kotlin.math.abs(force.expression - previousExpression) >= 2
                                    ) {
                                        lastExpressions[pointerId] = force.expression
                                        latestOnKeyPressure.value(pointerId, force.expression)
                                    }
                                } else {
                                    activeForces.remove(pointerId)
                                    lastExpressions.remove(pointerId)
                                }
                            }

                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                event.changes.forEach { change ->
                                    val pointerId = change.id.value
                                    val hardwarePressureHint =
                                        change.type == PointerType.Stylus || change.type == PointerType.Eraser
                                    val historicalPressure = lastRawPressures[pointerId] ?: change.pressure

                                    if (change.previousPressed) {
                                        change.historical.forEach { historical ->
                                            processSample(
                                                pointerId = pointerId,
                                                position = historical.position,
                                                uptimeMillis = historical.uptimeMillis,
                                                pressed = true,
                                                rawPressure = historicalPressure,
                                                hardwarePressureHint = hardwarePressureHint,
                                            )
                                        }
                                    }

                                    if (change.pressed) {
                                        lastRawPressures[pointerId] = change.pressure
                                    }
                                    processSample(
                                        pointerId = pointerId,
                                        position = change.position,
                                        uptimeMillis = change.uptimeMillis,
                                        pressed = change.pressed,
                                        rawPressure = change.pressure,
                                        hardwarePressureHint = hardwarePressureHint,
                                    )
                                    if (!change.pressed) {
                                        forceTrackers.remove(pointerId)
                                        lastRawPressures.remove(pointerId)
                                    }
                                    change.consume()
                                }
                            }
                        }
                    } finally {
                        val eventTimeMillis = SystemClock.uptimeMillis()
                        activeCoordinates.keys.toList().forEach {
                            latestOnKeyUp.value(it, eventTimeMillis, false)
                        }
                        activeCoordinates.clear()
                        activeForces.clear()
                        forceTrackers.clear()
                        lastExpressions.clear()
                        lastRawPressures.clear()
                    }
                },
        ) {
            val forceByCoordinate = mutableMapOf<AxialCoordinate, Float>()
            activeCoordinates.forEach { (pointerId, coordinate) ->
                val force = activeForces[pointerId] ?: TouchForce.Fixed.normalized
                forceByCoordinate[coordinate] = max(forceByCoordinate[coordinate] ?: 0f, force)
            }
            val playbackMode = playbackTimeline != null
            val playbackFrame = playbackTimeline?.visualFrameAt(
                positionSeconds = playbackPositionSeconds,
                activeScoreIndices = activePlaybackNoteIndices,
            ) ?: PlaybackVisualFrame.Empty
            drawKeyboard(
                layout = layout,
                transform = transform,
                displayMode = displayMode,
                selectedCoordinates = if (playbackMode) {
                    forceByCoordinate.keys
                } else {
                    selectedCoordinates + forceByCoordinate.keys
                },
                selectionAnchorCoordinate = selectionAnchorCoordinate.takeUnless { playbackMode },
                activeForces = forceByCoordinate,
                playbackMode = playbackMode,
                playbackFrame = playbackFrame,
                playbackPositionSeconds = playbackPositionSeconds,
            )
        }
    }
}

private fun modelBounds(layout: HexaKeyboardLayout): ModelBounds {
    if (layout.cells.isEmpty()) return ModelBounds(-1.0, 1.0, -1.0, 1.0)
    return ModelBounds(
        minX = min(layout.keyBounds.minX, layout.windowOutline.bounds.minX),
        maxX = max(layout.keyBounds.maxX, layout.windowOutline.bounds.maxX),
        minY = min(layout.keyBounds.minY, layout.windowOutline.bounds.minY),
        maxY = max(layout.keyBounds.maxY, layout.windowOutline.bounds.maxY),
    )
}

private fun canvasTransform(
    bounds: ModelBounds,
    size: Size,
    scaleMultiplier: Float,
    requestedPan: Offset,
    edgeMargin: Float,
): CanvasTransform {
    val fittedScale = min(
        max(1f, size.width) / max(1.0, bounds.width).toFloat(),
        max(1f, size.height) / max(1.0, bounds.height).toFloat(),
    )
    val scale = fittedScale * scaleMultiplier.coerceIn(MIN_KEYBOARD_SCALE, MAX_KEYBOARD_SCALE)
    val contentWidth = bounds.width.toFloat() * scale
    val contentHeight = bounds.height.toFloat() * scale
    val viewportPan = constrainKeyboardPan(
        requestedPan = requestedPan,
        contentSize = Size(contentWidth, contentHeight),
        viewportSize = size,
        edgeMargin = edgeMargin,
    )
    return CanvasTransform(
        scale = scale,
        offsetX = (size.width - contentWidth) / 2f - bounds.minX.toFloat() * scale + viewportPan.x,
        offsetY = (size.height - contentHeight) / 2f - bounds.minY.toFloat() * scale + viewportPan.y,
        viewportPan = viewportPan,
    )
}

private fun DrawScope.drawKeyboard(
    layout: HexaKeyboardLayout,
    transform: CanvasTransform,
    displayMode: KeyboardDisplayMode,
    selectedCoordinates: Set<AxialCoordinate>,
    selectionAnchorCoordinate: AxialCoordinate?,
    activeForces: Map<AxialCoordinate, Float>,
    playbackMode: Boolean,
    playbackFrame: PlaybackVisualFrame,
    playbackPositionSeconds: Double,
) {
    drawOrigin(transform)

    val selectedPitch = selectionAnchorCoordinate
        ?.let(layout::cellAt)
        ?.pitchClass
    val radius = (layout.configuration.radius.toFloat() - 1.5f) * transform.scale
    val primaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        color = HexaPalette.PrimaryDark.toArgb()
    }

    layout.cells.forEach { key ->
        val center = transform.point(key.center)
        val activeForce = activeForces[key.coordinate]
        val samePeriod = displayMode == KeyboardDisplayMode.Period && key.pitchClass == selectedPitch
        val playbackVisual = playbackFrame.keys[key.coordinate]
        val playbackTone = playbackTone(key, layout, displayMode)
        val path = hexagonPath(
            center = center,
            radius = radius,
            rotationDegrees = layout.configuration.rotationDegrees.toFloat(),
        )

        drawPath(
            path = path,
            color = if (playbackMode) playbackTone.dimColor() else {
                fillColor(key, layout, displayMode, samePeriod)
            },
        )
        if (playbackMode && playbackVisual != null) {
            drawPlaybackFill(
                path = path,
                center = center,
                radius = radius,
                tone = playbackTone,
                visual = playbackVisual,
            )
        }
        if (activeForce != null) {
            drawPath(
                path = path,
                color = HexaPalette.Selection.copy(alpha = 0.08f + activeForce * 0.20f),
            )
        }
        drawPath(
            path = path,
            color = HexaPalette.LineDark,
            style = Stroke(width = max(1f, radius * 0.045f)),
        )

        drawKeyLabel(
            key = key,
            center = center,
            radius = radius,
            mode = displayMode,
            paint = primaryPaint,
        )
    }

    if (!playbackMode && displayMode == KeyboardDisplayMode.Period) {
        drawPeriodVectors(layout, selectionAnchorCoordinate, transform)
    }
    if (playbackMode) {
        drawPlaybackEffects(
            layout = layout,
            transform = transform,
            frame = playbackFrame,
            positionSeconds = playbackPositionSeconds,
            radius = radius,
            displayMode = displayMode,
        )
    }
    drawSelectionOutlines(
        layout = layout,
        transform = transform,
        selectedCoordinates = selectedCoordinates,
        activeForces = activeForces,
        radius = radius,
    )
}

private data class PlaybackKeyTone(
    val hue: Float,
    val saturation: Float,
) {
    fun color(value: Float, alpha: Float = 1f): Color = Color.hsv(
        hue = hue,
        saturation = saturation,
        value = value.coerceIn(0f, 1f),
        alpha = alpha.coerceIn(0f, 1f),
    )

    fun dimColor(): Color = color(PLAYBACK_DIM_VALUE)
}

private fun playbackTone(
    key: HexKey,
    layout: HexaKeyboardLayout,
    displayMode: KeyboardDisplayMode,
): PlaybackKeyTone {
    val (index, count) = when (displayMode) {
        KeyboardDisplayMode.Coordinates ->
            Math.floorMod(key.coordinate.q * 2 + key.coordinate.r * 3, 9) to 9

        KeyboardDisplayMode.Pitch,
        KeyboardDisplayMode.Period -> key.pitchClass to max(1, layout.configuration.period)
    }
    val saturation = when (displayMode) {
        KeyboardDisplayMode.Coordinates -> 0.38f
        KeyboardDisplayMode.Pitch,
        KeyboardDisplayMode.Period -> 0.62f
    }
    return PlaybackKeyTone(
        hue = toneHue(index, count),
        saturation = saturation,
    )
}

private fun DrawScope.drawPlaybackFill(
    path: Path,
    center: Offset,
    radius: Float,
    tone: PlaybackKeyTone,
    visual: PlaybackKeyVisual,
) {
    val flash = visual.flash.coerceIn(0f, 1f)
    if (visual.isActive) {
        drawPath(
            path = path,
            color = tone.color(PLAYBACK_ACTIVE_VALUE + flash * PLAYBACK_FLASH_VALUE_BOOST),
        )
        return
    }

    visual.upcoming?.let { upcoming ->
        val progress = upcoming.progress.coerceIn(0f, 1f)
        val sweep = progress * 360f
        if (sweep > 0.1f) {
            val arcRadius = radius * 1.08f
            val outerValue = PLAYBACK_DIM_VALUE +
                (PLAYBACK_PREVIEW_VALUE - PLAYBACK_DIM_VALUE) * progress
            val middleValue = PLAYBACK_DIM_VALUE +
                (outerValue - PLAYBACK_DIM_VALUE) * 0.58f
            val previewBrush = Brush.radialGradient(
                colors = listOf(
                    tone.color(PLAYBACK_DIM_VALUE),
                    tone.color(middleValue),
                    tone.color(outerValue),
                ),
                center = center,
                radius = arcRadius,
            )
            clipPath(path) {
                drawArc(
                    brush = previewBrush,
                    startAngle = -90f - sweep / 2f,
                    sweepAngle = sweep,
                    useCenter = true,
                    topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                    size = Size(arcRadius * 2f, arcRadius * 2f),
                )
            }
        }
    }

    if (flash > 0.01f) {
        drawPath(
            path = path,
            color = tone.color(
                value = PLAYBACK_ACTIVE_VALUE + flash * PLAYBACK_FLASH_VALUE_BOOST,
                alpha = flash * 0.34f,
            ),
        )
    }
}

private fun DrawScope.drawPlaybackEffects(
    layout: HexaKeyboardLayout,
    transform: CanvasTransform,
    frame: PlaybackVisualFrame,
    positionSeconds: Double,
    radius: Float,
    displayMode: KeyboardDisplayMode,
) {
    frame.keys.forEach { (coordinate, visual) ->
        val key = layout.cellAt(coordinate) ?: return@forEach
        val center = transform.point(key.center)
        val tone = playbackTone(key, layout, displayMode)
        if (visual.isActive) {
            drawPlaybackTrackOutlines(
                center = center,
                radius = radius,
                rotationDegrees = layout.configuration.rotationDegrees.toFloat(),
                tracks = visual.activeTracks,
                flash = visual.flash,
            )
            drawActivePlaybackParticles(
                center = center,
                radius = radius,
                tone = tone,
                visual = visual,
                positionSeconds = positionSeconds,
            )
        }
        if (visual.completedNotes.isNotEmpty()) {
            drawCompletedPlaybackParticles(
                center = center,
                radius = radius,
                tone = tone,
                visual = visual,
            )
        }
    }
}

private fun DrawScope.drawPlaybackTrackOutlines(
    center: Offset,
    radius: Float,
    rotationDegrees: Float,
    tracks: List<Int>,
    flash: Float,
) {
    if (tracks.isEmpty()) return
    val strokeWidth = max(1.35f, radius * 0.052f)
    val layerSpacing = max(strokeWidth * 1.62f, radius * 0.068f)
    tracks.take(MAX_PLAYBACK_TRACK_LAYERS).forEachIndexed { index, track ->
        val layerRadius = max(radius * 0.42f, radius * 1.01f - index * layerSpacing)
        val path = hexagonPath(center, layerRadius, rotationDegrees)
        val color = trackColor(track)
        drawPath(
            path = path,
            color = color.copy(alpha = 0.20f + flash.coerceIn(0f, 1f) * 0.18f),
            style = Stroke(width = strokeWidth * 2.45f),
        )
        drawPath(
            path = path,
            color = color.copy(alpha = 0.86f + flash.coerceIn(0f, 1f) * 0.14f),
            style = Stroke(width = strokeWidth),
        )
    }
}

private fun DrawScope.drawActivePlaybackParticles(
    center: Offset,
    radius: Float,
    tone: PlaybackKeyTone,
    visual: PlaybackKeyVisual,
    positionSeconds: Double,
) {
    val flash = visual.flash.coerceIn(0f, 1f)
    visual.activeNotes.take(MAX_ACTIVE_PARTICLE_NOTES).forEach { note ->
        val emphasized = note.repeatedHit || flash >= 0.34f
        val particleCount = if (emphasized) 6 else 2
        val elapsed = (positionSeconds - note.start).coerceAtLeast(0.0)
        val velocityRatio = note.velocity.coerceIn(1, 127) / 127f
        repeat(particleCount) { particleIndex ->
            val seed = playbackParticleSeed(note, particleIndex, ACTIVE_PARTICLE_SALT)
            val phaseSeed = deterministicUnit(seed)
            val rate = 0.50 + deterministicUnit(seed xor 0x13579BDF) * 0.38
            val phase = ((elapsed * rate + phaseSeed) % 1.0).toFloat()
            val spread = (deterministicUnit(seed xor 0x2468ACE) - 0.5f) * PI.toFloat() * 0.92f
            val flutter = sin((phase + phaseSeed) * PI.toFloat() * 2f) * 0.10f
            val angle = -PI.toFloat() / 2f + spread + flutter
            val distance = radius * (0.10f + phase * 0.72f)
            val position = Offset(
                x = center.x + cos(angle) * distance,
                y = center.y + sin(angle) * distance - radius * phase * 0.08f,
            )
            val alpha = (1f - phase) *
                (0.24f + velocityRatio * 0.24f + flash * 0.38f)
            val particleRadius = max(
                0.85f,
                radius * (0.022f + deterministicUnit(seed xor 0x1020304) * 0.030f) *
                    (1f - phase * 0.42f),
            )
            drawCircle(
                color = tone.color(
                    value = 0.82f + flash * 0.16f,
                    alpha = alpha,
                ),
                radius = particleRadius,
                center = position,
            )
        }
    }
}

private fun DrawScope.drawCompletedPlaybackParticles(
    center: Offset,
    radius: Float,
    tone: PlaybackKeyTone,
    visual: PlaybackKeyVisual,
) {
    visual.completedNotes.forEach { completed ->
        val note = completed.note
        val progress = completed.progress.coerceIn(0f, 1f)
        val fade = (1f - progress) * (1f - progress)
        val expansion = progress * (2f - progress)
        val particleCount = if (note.repeatedHit || visual.flash >= 0.34f) 18 else 10
        val velocityRatio = note.velocity.coerceIn(1, 127) / 127f
        repeat(particleCount) { particleIndex ->
            val seed = playbackParticleSeed(note, particleIndex, COMPLETED_PARTICLE_SALT)
            val direction = deterministicUnit(seed) * PI.toFloat() * 2f
            val speed = 0.68f + deterministicUnit(seed xor 0x3141592) * 0.62f
            val distance = radius * (0.12f + expansion * speed)
            val gravity = radius * progress * progress *
                (0.06f + deterministicUnit(seed xor 0x2718281) * 0.20f)
            val position = Offset(
                x = center.x + cos(direction) * distance,
                y = center.y + sin(direction) * distance + gravity,
            )
            val particleRadius = max(
                0.75f,
                radius * (0.025f + deterministicUnit(seed xor 0x55AA55A) * 0.045f) *
                    (1f - progress * 0.48f),
            )
            drawCircle(
                color = tone.color(
                    value = 0.84f + velocityRatio * 0.14f,
                    alpha = fade * (0.58f + velocityRatio * 0.34f),
                ),
                radius = particleRadius,
                center = position,
            )
        }
    }
}

private fun trackColor(track: Int): Color = Color.hsv(
    hue = if (track in PLAYBACK_TRACK_HUES.indices) {
        PLAYBACK_TRACK_HUES[track]
    } else {
        positiveHue(PLAYBACK_TRACK_HUES.first() + track * PLAYBACK_TRACK_GOLDEN_ANGLE)
    },
    saturation = 0.86f,
    value = 0.98f,
)

private fun positiveHue(value: Float): Float {
    val remainder = value % 360f
    return if (remainder >= 0f) remainder else remainder + 360f
}

private fun playbackParticleSeed(
    note: KeyboardPlaybackNote,
    particleIndex: Int,
    salt: Int,
): Int =
    note.scoreIndex * 73_856_093 xor
        note.track * 19_349_663 xor
        note.coordinate.q * 83_492_791 xor
        note.coordinate.r * 49_979_687 xor
        particleIndex * 961_748_927 xor
        salt

private fun deterministicUnit(seed: Int): Float {
    var value = seed
    value = value xor (value ushr 16)
    value *= -2_048_144_789
    value = value xor (value ushr 13)
    value *= -1_028_477_387
    value = value xor (value ushr 16)
    return ((value ushr 8) and 0x00FF_FFFF) / 16_777_215f
}

private fun DrawScope.drawSelectionOutlines(
    layout: HexaKeyboardLayout,
    transform: CanvasTransform,
    selectedCoordinates: Set<AxialCoordinate>,
    activeForces: Map<AxialCoordinate, Float>,
    radius: Float,
) {
    selectedCoordinates.forEach { coordinate ->
        val key = layout.cellAt(coordinate) ?: return@forEach
        val activeForce = activeForces[coordinate]
        val strokeWidth = max(
            2.4f,
            radius * if (activeForce == null) 0.12f else 0.12f + activeForce * 0.08f,
        )
        val path = hexagonPath(
            center = transform.point(key.center),
            radius = radius,
            rotationDegrees = layout.configuration.rotationDegrees.toFloat(),
        )
        drawPath(
            path = path,
            color = HexaPalette.Selection.copy(alpha = 0.32f),
            style = Stroke(width = strokeWidth + 2f),
        )
        drawPath(
            path = path,
            color = HexaPalette.Selection,
            style = Stroke(width = strokeWidth),
        )
    }
}

private fun DrawScope.drawOrigin(transform: CanvasTransform) {
    val origin = transform.point(HexPoint(0.0, 0.0))
    val arm = 8f
    drawLine(HexaPalette.Accent.copy(alpha = 0.72f), origin - Offset(arm, 0f), origin + Offset(arm, 0f), 1.4f)
    drawLine(HexaPalette.Accent.copy(alpha = 0.72f), origin - Offset(0f, arm), origin + Offset(0f, arm), 1.4f)
    drawCircle(HexaPalette.Accent, radius = 2.5f, center = origin)
}

private fun DrawScope.drawPeriodVectors(
    layout: HexaKeyboardLayout,
    selectedCoordinate: AxialCoordinate?,
    transform: CanvasTransform,
) {
    val selected = selectedCoordinate?.let(layout::cellAt) ?: return
    val start = transform.point(selected.center)
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = 11f
        color = HexaPalette.Selection.toArgb()
    }

    layout.periodVectors.forEachIndexed { index, vector ->
        val targetCoordinate = AxialCoordinate(
            q = selected.coordinate.q + vector.dq,
            r = selected.coordinate.r + vector.dr,
        )
        val target = layout.cellAt(targetCoordinate) ?: return@forEachIndexed
        val end = transform.point(target.center)
        drawLine(
            color = HexaPalette.Selection.copy(alpha = 0.92f),
            start = start,
            end = end,
            strokeWidth = 2f,
            cap = StrokeCap.Round,
        )
        drawArrowHead(start, end)
        val label = Offset(
            x = start.x * 0.45f + end.x * 0.55f,
            y = start.y * 0.45f + end.y * 0.55f - 12f,
        )
        drawContext.canvas.nativeCanvas.drawText("P${index + 1}", label.x, label.y, labelPaint)
    }
}

private fun DrawScope.drawArrowHead(start: Offset, end: Offset) {
    val angle = atan2(end.y - start.y, end.x - start.x)
    val length = 8f
    val spread = (PI / 6.0).toFloat()
    val first = Offset(
        x = end.x - length * cos(angle - spread),
        y = end.y - length * sin(angle - spread),
    )
    val second = Offset(
        x = end.x - length * cos(angle + spread),
        y = end.y - length * sin(angle + spread),
    )
    drawLine(HexaPalette.Selection, end, first, 2f, cap = StrokeCap.Round)
    drawLine(HexaPalette.Selection, end, second, 2f, cap = StrokeCap.Round)
}

private fun DrawScope.drawKeyLabel(
    key: HexKey,
    center: Offset,
    radius: Float,
    mode: KeyboardDisplayMode,
    paint: Paint,
) {
    if (radius < 5f) return
    if (mode == KeyboardDisplayMode.Coordinates) return

    val label = key.pitchClass.toString()
    paint.textSize = max(7f, radius * 0.43f)
    val baseline = center.y - (paint.ascent() + paint.descent()) / 2f
    drawContext.canvas.nativeCanvas.drawText(label, center.x, baseline, paint)
}

private fun fillColor(
    key: HexKey,
    layout: HexaKeyboardLayout,
    mode: KeyboardDisplayMode,
    samePeriod: Boolean,
): Color = when (mode) {
    KeyboardDisplayMode.Coordinates -> {
        val index = Math.floorMod(key.coordinate.q * 2 + key.coordinate.r * 3, 9)
        toneColor(index, 9, saturation = 0.38f, value = 0.34f)
    }

    KeyboardDisplayMode.Pitch -> toneColor(
        index = key.pitchClass,
        count = max(1, layout.configuration.period),
        saturation = 0.62f,
        value = 0.43f,
    )

    KeyboardDisplayMode.Period -> toneColor(
        index = key.pitchClass,
        count = max(1, layout.configuration.period),
        saturation = if (samePeriod) 0.70f else 0.32f,
        value = if (samePeriod) 0.48f else 0.27f,
    )
}

private fun toneColor(index: Int, count: Int, saturation: Float, value: Float): Color {
    return Color.hsv(
        hue = toneHue(index, count),
        saturation = saturation,
        value = value,
    )
}

private fun toneHue(index: Int, count: Int): Float {
    val safeCount = max(1, count)
    return Math.floorMod(index, safeCount).toFloat() / safeCount.toFloat() * 360f
}

private fun hexagonPath(center: Offset, radius: Float, rotationDegrees: Float): Path = Path().apply {
    repeat(6) { index ->
        val angle = (index * 60f + rotationDegrees) * PI.toFloat() / 180f
        val point = Offset(
            x = center.x + radius * cos(angle),
            y = center.y + radius * sin(angle),
        )
        if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
    }
    close()
}

private val PLAYBACK_TRACK_HUES = floatArrayOf(190f, 28f, 132f, 48f, 264f, 158f, 330f, 88f)
private const val PLAYBACK_TRACK_GOLDEN_ANGLE = 137.508f
private const val PLAYBACK_DIM_VALUE = 0.22f
private const val PLAYBACK_PREVIEW_VALUE = 0.76f
private const val PLAYBACK_ACTIVE_VALUE = 0.88f
private const val PLAYBACK_FLASH_VALUE_BOOST = 0.26f
private const val MAX_PLAYBACK_TRACK_LAYERS = 8
private const val MAX_ACTIVE_PARTICLE_NOTES = 3
private const val ACTIVE_PARTICLE_SALT = 0x1A2B3C4D
private const val COMPLETED_PARTICLE_SALT = 0x4D3C2B1A

