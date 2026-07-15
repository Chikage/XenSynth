package icu.ringona.xensynth

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import icu.ringona.xensynth.hexkeyboard.core.HexKey
import icu.ringona.xensynth.hexkeyboard.core.HexaKeyboardConfiguration
import icu.ringona.xensynth.hexkeyboard.core.HexaKeyboardLayoutEngine
import icu.ringona.xensynth.hexkeyboard.core.TouchSelectionState
import icu.ringona.xensynth.hexkeyboard.playback.activeScoreIndicesAt
import icu.ringona.xensynth.hexkeyboard.playback.PLAYBACK_COMPLETION_BURST_SECONDS
import icu.ringona.xensynth.hexkeyboard.playback.PLAYBACK_PREVIEW_SECONDS
import icu.ringona.xensynth.hexkeyboard.playback.PLAYBACK_PREVIEW_SECONDS_MAX
import icu.ringona.xensynth.hexkeyboard.playback.PLAYBACK_PREVIEW_SECONDS_MIN
import icu.ringona.xensynth.hexkeyboard.playback.snapToKeyboard
import icu.ringona.xensynth.hexkeyboard.ui.HexKeyboardCanvas
import icu.ringona.xensynth.hexkeyboard.ui.KeyboardDisplayMode
import icu.ringona.xensynth.hexkeyboard.ui.MIN_KEYBOARD_SCALE
import icu.ringona.xensynth.hexkeyboard.ui.MAX_KEYBOARD_SCALE
import icu.ringona.xensynth.hexkeyboard.ui.toolbarDragToKeyboardPan
import icu.ringona.xensynth.midi.ParsedScore
import icu.ringona.xensynth.view.ScaleGuide

internal const val HEX_KEYBOARD_REFERENCE_MIDI_PITCH = 60.0
internal const val HEX_KEYBOARD_FREE_TUNING_PERIOD = 12

internal fun hexKeyboardRawPitch(step: Int, edo: Int): Double {
    val period = edo.takeIf { it > 0 } ?: HEX_KEYBOARD_FREE_TUNING_PERIOD
    return HEX_KEYBOARD_REFERENCE_MIDI_PITCH + step * 12.0 / period
}

internal fun hexKeyboardPlaybackPitch(
    step: Int,
    edo: Int,
    scaleGuide: ScaleGuide,
    offsetCents: Double = 0.0
): Double? {
    return scaleGuide.touchPitchForRaw(edo, hexKeyboardRawPitch(step, edo))
        ?.minus(offsetCents / 100.0)
}

@Stable
internal class HexKeyboardHostState {
    var configuration by mutableStateOf(HexaKeyboardConfiguration.Default)
        private set
    var touchSensitivity by mutableFloatStateOf(1.2f)
        private set
    var pseudoPressureEnabled by mutableStateOf(true)
        private set
    var previewSeconds by mutableDoubleStateOf(PLAYBACK_PREVIEW_SECONDS)
        private set
    var edo by mutableIntStateOf(MainActivity.EDO_DEFAULT)
        private set
    var pitchOffsetCents by mutableDoubleStateOf(0.0)
        private set
    var scaleGuide by mutableStateOf<ScaleGuide?>(null)
        private set
    var score by mutableStateOf<ParsedScore?>(null)
        private set
    var playheadSeconds by mutableDoubleStateOf(0.0)
        private set
    var keyboardScale by mutableFloatStateOf(MIN_KEYBOARD_SCALE)
        private set
    var keyboardPan by mutableStateOf(Offset.Zero)
        private set
    var playing by mutableStateOf(false)
        private set
    var finished by mutableStateOf(false)
        private set
    var touchEpoch by mutableLongStateOf(0L)
        private set

    fun applySettings(
        columns: Int,
        rows: Int,
        edo: Int,
        stepQ: Int,
        stepR: Int,
        groupByOctave: Boolean,
        touchSensitivityPercent: Int,
        pseudoPressureEnabled: Boolean,
        previewSeconds: Double,
        scaleGuide: ScaleGuide
    ) {
        val nextConfiguration = HexaKeyboardConfiguration(
            columns = columns,
            rows = rows,
            period = edo.takeIf { it > 0 } ?: HEX_KEYBOARD_FREE_TUNING_PERIOD,
            stepQ = stepQ,
            stepR = stepR,
            groupByOctave = groupByOctave,
            rotationDegrees = FIXED_ROTATION_DEGREES
        ).normalized()
        if (configuration != nextConfiguration) {
            configuration = nextConfiguration
            keyboardPan = Offset.Zero
        }
        touchSensitivity = (touchSensitivityPercent / 100f).coerceIn(1f, 1.5f)
        this.pseudoPressureEnabled = pseudoPressureEnabled
        this.previewSeconds = previewSeconds
            .takeIf { it.isFinite() }
            ?.coerceIn(PLAYBACK_PREVIEW_SECONDS_MIN, PLAYBACK_PREVIEW_SECONDS_MAX)
            ?: PLAYBACK_PREVIEW_SECONDS
        this.edo = edo
        this.scaleGuide = scaleGuide
    }

    fun updateScore(score: ParsedScore?) {
        this.score = score
    }

    fun updatePlayhead(seconds: Double) {
        playheadSeconds = seconds
    }

    fun updatePlaybackState(playing: Boolean, finished: Boolean) {
        this.playing = playing
        this.finished = finished
    }

    fun updatePitchOffset(cents: Double) {
        pitchOffsetCents = cents.takeIf { it.isFinite() } ?: 0.0
    }

    fun panBy(delta: Offset) {
        keyboardPan += delta
    }

    fun zoomBy(factor: Float) {
        if (factor.isFinite() && factor > 0f) {
            keyboardScale = (keyboardScale * factor).coerceIn(
                MIN_KEYBOARD_SCALE,
                MAX_KEYBOARD_SCALE
            )
        }
    }

    fun updateConstrainedPan(pan: Offset) {
        keyboardPan = pan
    }

    fun resetViewport() {
        keyboardScale = MIN_KEYBOARD_SCALE
        keyboardPan = Offset.Zero
    }

    fun cancelTouches() {
        touchEpoch += 1L
    }

    private companion object {
        const val FIXED_ROTATION_DEGREES = 12
    }
}

@Composable
internal fun HexKeyboardHost(
    state: HexKeyboardHostState,
    onKeyDown: (pointerId: Long, key: HexKey, velocity: Int) -> Unit,
    onKeyPressure: (pointerId: Long, expression: Int) -> Unit,
    onKeyUp: (pointerId: Long) -> Unit
) {
    val layout = remember(state.configuration) {
        HexaKeyboardLayoutEngine.build(state.configuration)
    }
    var touchSelection by remember(layout) {
        mutableStateOf(TouchSelectionState(anchorCoordinate = layout.defaultSelection?.coordinate))
    }
    val playbackTimeline = remember(
        state.score,
        layout,
        state.edo,
        state.scaleGuide,
        state.pitchOffsetCents
    ) {
        val guide = state.scaleGuide
        if (guide == null) {
            state.score?.snapToKeyboard(layout) { key ->
                hexKeyboardRawPitch(key.step, state.edo) - state.pitchOffsetCents / 100.0
            }
        } else {
            state.score?.snapToKeyboard(layout) { key ->
                hexKeyboardPlaybackPitch(
                    step = key.step,
                    edo = state.edo,
                    scaleGuide = guide,
                    offsetCents = state.pitchOffsetCents
                )
            }
        }
    }
    var visualTailSeconds by remember(state.score) { mutableDoubleStateOf(0.0) }
    LaunchedEffect(state.finished, state.score) {
        visualTailSeconds = 0.0
        if (!state.finished || state.score == null) return@LaunchedEffect
        val startedAt = withFrameNanos { it }
        while (true) {
            val elapsed = withFrameNanos { it }
                .minus(startedAt)
                .coerceAtLeast(0L) / 1_000_000_000.0
            visualTailSeconds = elapsed.coerceAtMost(PLAYBACK_COMPLETION_BURST_SECONDS)
            if (elapsed >= PLAYBACK_COMPLETION_BURST_SECONDS) break
        }
    }
    val visualPlayheadSeconds = state.playheadSeconds + visualTailSeconds
    val activeScoreIndices = remember(playbackTimeline, visualPlayheadSeconds, state.playing) {
        if (state.playing) {
            playbackTimeline?.activeScoreIndicesAt(visualPlayheadSeconds).orEmpty()
        } else {
            emptySet()
        }
    }

    LaunchedEffect(layout) {
        touchSelection = touchSelection.retainCoordinates(
            validCoordinates = layout.cells.mapTo(HashSet(layout.cells.size)) { it.coordinate },
            fallbackCoordinate = layout.defaultSelection?.coordinate
        )
    }

    HexKeyboardCanvas(
        touchEpoch = state.touchEpoch,
        layout = layout,
        keyboardScale = state.keyboardScale,
        keyboardPan = state.keyboardPan,
        onKeyboardPanChange = state::updateConstrainedPan,
        touchSensitivity = state.touchSensitivity,
        pseudoPressureEnabled = state.pseudoPressureEnabled,
        displayMode = KeyboardDisplayMode.Pitch,
        selectedCoordinates = if (state.score != null) {
            emptySet()
        } else {
            touchSelection.selectedCoordinates
        },
        selectionAnchorCoordinate = touchSelection.anchorCoordinate.takeUnless {
            state.score != null
        },
        playbackTimeline = playbackTimeline,
        playbackPositionSeconds = visualPlayheadSeconds,
        playbackPreviewSeconds = state.previewSeconds,
        activePlaybackNoteIndices = activeScoreIndices,
        modifier = Modifier.fillMaxSize(),
        onKeyDown = { pointerId, key, velocity, eventTimeMillis ->
            touchSelection = touchSelection.press(
                pointerId = pointerId,
                coordinate = key.coordinate,
                eventTimeMillis = eventTimeMillis
            )
            onKeyDown(pointerId, key, velocity)
        },
        onKeyPressure = onKeyPressure,
        onKeyUp = { pointerId, eventTimeMillis, retainForChord ->
            touchSelection = touchSelection.release(
                pointerId = pointerId,
                eventTimeMillis = eventTimeMillis,
                retainForChord = retainForChord
            )
            onKeyUp(pointerId)
        }
    )
}

internal suspend fun PointerInputScope.detectHexKeyboardViewportGestures(
    onPan: (Offset) -> Unit,
    onZoom: (Float) -> Unit
) {
    awaitEachGesture {
        var gestureClaimed = false
        var accumulatedPan = Offset.Zero

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressedPointerCount = event.changes.count { it.pressed }
            val pan = event.calculatePan()
            val zoom = if (pressedPointerCount >= 2) event.calculateZoom() else 1f

            if (!gestureClaimed) {
                accumulatedPan += pan
                gestureClaimed = pressedPointerCount >= 2 ||
                    accumulatedPan.getDistance() > viewConfiguration.touchSlop
            }
            if (gestureClaimed) {
                if (pressedPointerCount >= 2 && zoom.isFinite() && zoom > 0f && zoom != 1f) {
                    onZoom(zoom)
                }
                if (pan != Offset.Zero) {
                    onPan(toolbarDragToKeyboardPan(pan))
                }
                event.changes.forEach { it.consume() }
            }
            if (event.changes.none { it.pressed }) break
        }
    }
}
