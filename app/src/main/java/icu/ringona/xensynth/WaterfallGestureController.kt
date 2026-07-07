package icu.ringona.xensynth

import android.view.MotionEvent
import icu.ringona.xensynth.audio.NativeAudio
import icu.ringona.xensynth.audio.NativeAudioEngine
import icu.ringona.xensynth.view.WaterfallDisplayState
import icu.ringona.xensynth.view.WaterfallMetrics
import icu.ringona.xensynth.view.WaterfallPreviewNote
import icu.ringona.xensynth.view.WaterfallSurface
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

class WaterfallGestureController(
    private val waterfallView: WaterfallSurface,
    private val callbacks: Callbacks,
    private val nativeAudio: NativeAudio = NativeAudioEngine
) {
    interface Callbacks {
        fun playheadSeconds(): Double
        fun beginSeekGesture()
        fun seekTo(seconds: Double)
        fun endSeekGesture()
        fun canTogglePlayback(): Boolean
        fun togglePlayback()
        fun canUseNativeAudio(): Boolean
        fun onAudioReadyChanged(ready: Boolean)
        fun onDisplayStateChanged(displayState: WaterfallDisplayState)
        fun previewProgram(): Int
        fun volumeGain(): Float
        fun setVolumeGain(gain: Float)
    }

    private var nativeGesture: NativeGesture? = null
    private var rulerPreviewTap: WaterfallTap? = null
    private val activeRulerPreviews = linkedMapOf<Int, RulerPreview>()

    fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val pointerId = event.getPointerId(0)
                if (startRulerPreview(pointerId, event.x, event.y)) {
                    nativeGesture = null
                    rulerPreviewTap = beginWaterfallTap(pointerId, event.x, event.y)
                } else {
                    rulerPreviewTap = null
                    nativeGesture = beginNativeDragOrTap(event)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val currentGesture = nativeGesture
                currentGesture?.tapActive = false
                rulerPreviewTap?.active = false
                val index = event.actionIndex
                val pointerId = event.getPointerId(index)
                if (!startRulerPreview(pointerId, event.getX(index), event.getY(index))) {
                    beginNativePinch(event)?.let {
                        endSeekGestureIfNeeded(currentGesture)
                        nativeGesture = it
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                updateRulerPreviews(event)
                updateRulerPreviewTap(event)
                val gesture = nativeGesture ?: return true
                if (gesture.pinch != null) {
                    updateNativePinch(event, gesture)
                } else {
                    updateNativeDrag(event, gesture)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                stopRulerPreview(event.getPointerId(event.actionIndex))
                if (rulerPreviewTap?.pointerId == event.getPointerId(event.actionIndex)) {
                    rulerPreviewTap = null
                }
                val gesture = nativeGesture
                if (gesture?.pinch != null || gesture?.pointerId == event.getPointerId(event.actionIndex)) {
                    endSeekGestureIfNeeded(gesture)
                    nativeGesture = null
                } else {
                    gesture?.tapActive = false
                }
            }
            MotionEvent.ACTION_UP -> {
                stopRulerPreview(event.getPointerId(0))
                finishNativeTouch(event)
            }
            MotionEvent.ACTION_CANCEL -> cancelTouchState()
        }
        return true
    }

    fun cancelTouchState() {
        stopAllRulerPreviews()
        endSeekGestureIfNeeded(nativeGesture)
        nativeGesture = null
        rulerPreviewTap = null
    }

    fun setOffsetCents(value: Float) {
        val next = value.coerceIn(
            -WaterfallMetrics.OFFSET_CENT_RANGE.toFloat(),
            WaterfallMetrics.OFFSET_CENT_RANGE.toFloat()
        )
        syncNativeDisplayState(waterfallView.setWaterfallOffset(next.toDouble()))
    }

    private fun beginWaterfallTap(pointerId: Int, x: Float, y: Float): WaterfallTap? {
        if (!isInsideWaterfallGestureArea(x, y)) {
            return null
        }
        return WaterfallTap(pointerId = pointerId, startX = x, startY = y)
    }

    private fun updateRulerPreviewTap(event: MotionEvent) {
        val tap = rulerPreviewTap ?: return
        val point = event.pointForPointerId(tap.pointerId) ?: run {
            tap.active = false
            return
        }
        if (max(abs(point.x - tap.startX), abs(point.y - tap.startY)) > GESTURE_THRESHOLD_PX) {
            tap.active = false
        }
    }

    private fun startRulerPreview(pointerId: Int, x: Float, y: Float): Boolean {
        val note = waterfallView.noteFromRulerTouchPoint(x, y, active = false) ?: return false
        stopRulerPreview(pointerId)
        waterfallView.beginManualPreview(pointerId, note)
        val nativeKey = startPreviewAudio(note)
        activeRulerPreviews[pointerId] = RulerPreview(
            note = note,
            visualNote = note,
            nativeKey = nativeKey,
            initialVelocity = note.velocity
        )
        return true
    }

    private fun updateRulerPreviews(event: MotionEvent) {
        if (activeRulerPreviews.isEmpty()) {
            return
        }
        for (index in 0 until event.pointerCount) {
            val pointerId = event.getPointerId(index)
            val active = activeRulerPreviews[pointerId] ?: continue
            val sampledNote = waterfallView.noteFromRulerTouchPoint(
                x = event.getX(index),
                y = event.getY(index),
                active = true,
                stickyVisualPitch = active.visualNote.visualPitch
            )
            if (sampledNote == null) {
                stopRulerPreview(pointerId)
                continue
            }
            val note = sampledNote.copy(velocity = active.initialVelocity)
            val pitchDeltaCents = abs(note.pitch - active.note.pitch) * 100.0
            waterfallView.updateManualPreview(pointerId, note)
            active.visualNote = note
            if (pitchDeltaCents < PREVIEW_UPDATE_CENTS) {
                continue
            }
            active.nativeKey?.let { stopPreviewAudio(it) }
            active.nativeKey = startPreviewAudio(note)
            active.note = note
        }
    }

    private fun stopRulerPreview(pointerId: Int) {
        val active = activeRulerPreviews.remove(pointerId) ?: return
        active.nativeKey?.let { stopPreviewAudio(it) }
        waterfallView.releaseManualPreview(pointerId)
    }

    private fun stopAllRulerPreviews() {
        activeRulerPreviews.keys.toList().forEach { stopRulerPreview(it) }
    }

    private fun startPreviewAudio(note: WaterfallPreviewNote): Int? {
        if (!runCatching { nativeAudio.isStarted() }.getOrDefault(false)) {
            val ready = runCatching { nativeAudio.restart() }.getOrDefault(false)
            callbacks.onAudioReadyChanged(ready)
        }
        if (callbacks.canUseNativeAudio()) {
            return runCatching {
                nativeAudio.noteOn(
                    key = note.midiPitch,
                    velocity = note.velocity,
                    cents = note.cents.toFloat(),
                    program = callbacks.previewProgram().coerceIn(MIDI_PROGRAM_MIN, MIDI_PROGRAM_MAX)
                )
            }.getOrNull()
        }
        return null
    }

    private fun stopPreviewAudio(key: Int) {
        runCatching { nativeAudio.noteOff(key) }
    }

    private fun beginNativeDragOrTap(event: MotionEvent): NativeGesture? {
        val x = event.x
        val y = event.y
        if (!isInsideWaterfallGestureArea(x, y)) {
            return null
        }
        val state = waterfallView.displayState()
        return NativeGesture(
            pointerId = event.getPointerId(0),
            startX = x,
            startY = y,
            startState = state,
            startPlayheadSeconds = callbacks.playheadSeconds(),
            startVolumeGain = callbacks.volumeGain(),
            tapActive = true,
            rightHalf = x >= waterfallView.surfaceWidth() / 2f,
            moveReference = max(GESTURE_REFERENCE_MIN_PX, waterfallView.surfaceWidth().toFloat()),
            volumeReference = max(GESTURE_REFERENCE_MIN_PX, waterfallView.waterfallGestureHeight()),
            seekReference = max(GESTURE_REFERENCE_MIN_PX, state.pixelsPerSecond.toFloat())
        )
    }

    private fun beginNativePinch(event: MotionEvent): NativeGesture? {
        val points = waterfallPointerPoints(event)
        if (points.size < 2) {
            return null
        }
        val first = points[0]
        val second = points[1]
        val state = waterfallView.displayState()
        val reference = max(
            GESTURE_REFERENCE_MIN_PX,
            min(waterfallView.surfaceWidth().toFloat(), waterfallView.surfaceHeight().toFloat()) * PINCH_REFERENCE_RATIO
        )
        return NativeGesture(
            pointerId = first.id,
            startX = first.x,
            startY = first.y,
            startState = state,
            startPlayheadSeconds = callbacks.playheadSeconds(),
            startVolumeGain = callbacks.volumeGain(),
            tapActive = false,
            rightHalf = false,
            moveReference = max(GESTURE_REFERENCE_MIN_PX, waterfallView.surfaceWidth().toFloat()),
            volumeReference = max(GESTURE_REFERENCE_MIN_PX, waterfallView.waterfallGestureHeight()),
            seekReference = max(GESTURE_REFERENCE_MIN_PX, state.pixelsPerSecond.toFloat()),
            handled = true,
            pinch = NativePinch(
                firstPointerId = first.id,
                secondPointerId = second.id,
                startAbsDx = abs(second.x - first.x),
                startAbsDy = abs(second.y - first.y),
                startPitchAxis = axisFromPitchZoom(state.pitchZoomScale),
                startTimeAxis = zoomAxisFromPixelsPerSecond(state.pixelsPerSecond),
                reference = reference.toDouble()
            )
        )
    }

    private fun updateNativePinch(event: MotionEvent, gesture: NativeGesture) {
        val pinch = gesture.pinch ?: return
        val first = event.pointForPointerId(pinch.firstPointerId) ?: return
        val second = event.pointForPointerId(pinch.secondPointerId) ?: return
        if (!isInsideWaterfallGestureArea(first.x, first.y) || !isInsideWaterfallGestureArea(second.x, second.y)) {
            return
        }
        val dxDelta = abs(second.x - first.x) - pinch.startAbsDx
        val dyDelta = abs(second.y - first.y) - pinch.startAbsDy
        if (pinch.axis == null) {
            if (max(abs(dxDelta), abs(dyDelta)) < GESTURE_THRESHOLD_PX) {
                return
            }
            pinch.axis = if (abs(dxDelta) >= abs(dyDelta)) PinchAxis.Pitch else PinchAxis.Time
        }
        val next = when (pinch.axis) {
            PinchAxis.Pitch -> waterfallView.setSpeedZoom(
                pixels = gesture.startState.pixelsPerSecond,
                pitchScale = pitchZoomFromAxis((pinch.startPitchAxis + dxDelta / pinch.reference).coerceIn(-1.0, 1.0))
            )
            PinchAxis.Time -> waterfallView.setSpeedZoom(
                pixels = pixelsPerSecondFromZoomAxis((pinch.startTimeAxis + dyDelta / pinch.reference).coerceIn(-1.0, 1.0)),
                pitchScale = gesture.startState.pitchZoomScale
            )
            null -> return
        }
        syncNativeDisplayState(next)
    }

    private fun updateNativeDrag(event: MotionEvent, gesture: NativeGesture) {
        val point = event.pointForPointerId(gesture.pointerId) ?: return
        val dx = point.x - gesture.startX
        val dy = point.y - gesture.startY
        if (max(abs(dx), abs(dy)) > GESTURE_THRESHOLD_PX) {
            gesture.tapActive = false
        }
        if (!isInsideWaterfallGestureArea(point.x, point.y)) {
            return
        }
        if (gesture.mode == null) {
            if (max(abs(dx), abs(dy)) < GESTURE_THRESHOLD_PX) {
                return
            }
            gesture.mode = if (abs(dy) > abs(dx)) {
                if (gesture.rightHalf) DragMode.Volume else DragMode.Seek
            } else {
                DragMode.Move
            }
            if (gesture.mode == DragMode.Seek) {
                callbacks.beginSeekGesture()
            }
            gesture.handled = true
        }
        val next = when (gesture.mode) {
            DragMode.Volume -> {
                callbacks.setVolumeGain(
                    gesture.startVolumeGain - dy / gesture.volumeReference * VOLUME_GAIN_GESTURE_RANGE
                )
                return
            }
            DragMode.Move -> {
                val panRange = WaterfallMetrics.pitchPanRange(gesture.startState.pitchZoomScale)
                waterfallView.setPitchPan(
                    gesture.startState.pitchPanSemitones -
                        dx / gesture.moveReference * panRange
                )
            }
            DragMode.Seek -> {
                callbacks.seekTo(gesture.startPlayheadSeconds + dy / gesture.seekReference)
                return
            }
            null -> return
        }
        syncNativeDisplayState(next)
    }

    private fun finishNativeTouch(event: MotionEvent) {
        val gesture = nativeGesture
        val tap = rulerPreviewTap
        endSeekGestureIfNeeded(gesture)
        nativeGesture = null
        rulerPreviewTap = null
        if (gesture?.pinch != null) {
            return
        }
        val waterfallTap = (
            gesture != null &&
                gesture.tapActive &&
                !gesture.handled &&
                event.pointForPointerId(gesture.pointerId)?.let { isInsideWaterfallGestureArea(it.x, it.y) } == true
            ) || (
            tap != null &&
                tap.active &&
                event.pointForPointerId(tap.pointerId)?.let { isInsideWaterfallGestureArea(it.x, it.y) } == true
            )
        if (waterfallTap) {
            if (!callbacks.canTogglePlayback()) {
                return
            }
            waterfallView.performWaterfallClick()
            callbacks.togglePlayback()
        }
    }

    private fun endSeekGestureIfNeeded(gesture: NativeGesture?) {
        if (gesture?.mode == DragMode.Seek) {
            callbacks.endSeekGesture()
        }
    }

    private fun syncNativeDisplayState(displayState: WaterfallDisplayState) {
        callbacks.onDisplayStateChanged(displayState)
    }

    private fun isInsideWaterfallGestureArea(x: Float, y: Float): Boolean {
        return x >= 0f &&
            x <= waterfallView.surfaceWidth().toFloat() &&
            y >= 0f &&
            y <= waterfallView.waterfallGestureHeight()
    }

    private fun waterfallPointerPoints(event: MotionEvent): List<PointerPoint> {
        val points = ArrayList<PointerPoint>(event.pointerCount)
        for (index in 0 until event.pointerCount) {
            val x = event.getX(index)
            val y = event.getY(index)
            if (isInsideWaterfallGestureArea(x, y)) {
                points += PointerPoint(event.getPointerId(index), x, y)
            }
        }
        return points
    }

    private fun MotionEvent.pointForPointerId(pointerId: Int): PointerPoint? {
        val index = findPointerIndex(pointerId)
        if (index < 0) {
            return null
        }
        return PointerPoint(pointerId, getX(index), getY(index))
    }

    private fun pixelsPerSecondFromZoomAxis(axis: Double): Double {
        val value = if (axis < 0.0) {
            WaterfallMetrics.DEFAULT_PIXELS_PER_SECOND +
                axis * (WaterfallMetrics.DEFAULT_PIXELS_PER_SECOND - WaterfallMetrics.TIME_ZOOM_MIN)
        } else {
            WaterfallMetrics.DEFAULT_PIXELS_PER_SECOND +
                axis * (WaterfallMetrics.TIME_ZOOM_MAX - WaterfallMetrics.DEFAULT_PIXELS_PER_SECOND)
        }
        return roundToStep(
            value.coerceIn(WaterfallMetrics.TIME_ZOOM_MIN, WaterfallMetrics.TIME_ZOOM_MAX),
            TIME_ZOOM_STEP
        )
    }

    private fun zoomAxisFromPixelsPerSecond(value: Double): Double {
        val pixels = value.coerceIn(WaterfallMetrics.TIME_ZOOM_MIN, WaterfallMetrics.TIME_ZOOM_MAX)
        return if (pixels < WaterfallMetrics.DEFAULT_PIXELS_PER_SECOND) {
            (pixels - WaterfallMetrics.DEFAULT_PIXELS_PER_SECOND) /
                (WaterfallMetrics.DEFAULT_PIXELS_PER_SECOND - WaterfallMetrics.TIME_ZOOM_MIN)
        } else {
            (pixels - WaterfallMetrics.DEFAULT_PIXELS_PER_SECOND) /
                (WaterfallMetrics.TIME_ZOOM_MAX - WaterfallMetrics.DEFAULT_PIXELS_PER_SECOND)
        }
    }

    private fun pitchZoomFromAxis(axis: Double): Double {
        val value = if (axis < 0.0) {
            WaterfallMetrics.PITCH_ZOOM_DEFAULT +
                axis * (WaterfallMetrics.PITCH_ZOOM_DEFAULT - WaterfallMetrics.PITCH_ZOOM_MIN)
        } else {
            WaterfallMetrics.PITCH_ZOOM_DEFAULT +
                axis * (WaterfallMetrics.PITCH_ZOOM_MAX - WaterfallMetrics.PITCH_ZOOM_DEFAULT)
        }
        return roundToStep(
            value.coerceIn(WaterfallMetrics.PITCH_ZOOM_MIN, WaterfallMetrics.PITCH_ZOOM_MAX),
            PITCH_ZOOM_STEP
        )
    }

    private fun axisFromPitchZoom(value: Double): Double {
        val scale = value.coerceIn(WaterfallMetrics.PITCH_ZOOM_MIN, WaterfallMetrics.PITCH_ZOOM_MAX)
        return if (scale < WaterfallMetrics.PITCH_ZOOM_DEFAULT) {
            (scale - WaterfallMetrics.PITCH_ZOOM_DEFAULT) /
                (WaterfallMetrics.PITCH_ZOOM_DEFAULT - WaterfallMetrics.PITCH_ZOOM_MIN)
        } else {
            (scale - WaterfallMetrics.PITCH_ZOOM_DEFAULT) /
                (WaterfallMetrics.PITCH_ZOOM_MAX - WaterfallMetrics.PITCH_ZOOM_DEFAULT)
        }
    }

    private fun roundToStep(value: Double, step: Double): Double {
        return round(value / step) * step
    }

    private data class PointerPoint(
        val id: Int,
        val x: Float,
        val y: Float
    )

    private data class WaterfallTap(
        val pointerId: Int,
        val startX: Float,
        val startY: Float,
        var active: Boolean = true
    )

    private data class RulerPreview(
        var note: WaterfallPreviewNote,
        var visualNote: WaterfallPreviewNote,
        var nativeKey: Int?,
        val initialVelocity: Int
    )

    private data class NativeGesture(
        val pointerId: Int,
        val startX: Float,
        val startY: Float,
        val startState: WaterfallDisplayState,
        val startPlayheadSeconds: Double,
        val startVolumeGain: Float,
        var tapActive: Boolean,
        val rightHalf: Boolean,
        val moveReference: Float,
        val volumeReference: Float,
        val seekReference: Float,
        var mode: DragMode? = null,
        var handled: Boolean = false,
        val pinch: NativePinch? = null
    )

    private data class NativePinch(
        val firstPointerId: Int,
        val secondPointerId: Int,
        val startAbsDx: Float,
        val startAbsDy: Float,
        val startPitchAxis: Double,
        val startTimeAxis: Double,
        val reference: Double,
        var axis: PinchAxis? = null
    )

    private enum class DragMode {
        Move,
        Volume,
        Seek
    }

    private enum class PinchAxis {
        Pitch,
        Time
    }

    private companion object {
        const val GESTURE_THRESHOLD_PX = 8f
        const val GESTURE_REFERENCE_MIN_PX = 80f
        const val PINCH_REFERENCE_RATIO = 0.42f
        const val TIME_ZOOM_STEP = 10.0
        const val PITCH_ZOOM_STEP = 0.05
        const val PREVIEW_UPDATE_CENTS = 6.0
        const val MIDI_PROGRAM_MIN = 0
        const val MIDI_PROGRAM_MAX = 127
        const val VOLUME_GAIN_GESTURE_RANGE = 6f
    }
}
