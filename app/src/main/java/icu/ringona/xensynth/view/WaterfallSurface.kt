package icu.ringona.xensynth.view

import android.view.View
import icu.ringona.xensynth.midi.ParsedScore

interface WaterfallSurface {
    val view: View

    fun surfaceWidth(): Int = view.width

    fun surfaceHeight(): Int = view.height

    fun performWaterfallClick(): Boolean = view.performClick()

    fun setScore(nextScore: ParsedScore?)
    fun setPlayhead(seconds: Double)
    fun syncPlayhead(seconds: Double) = setPlayhead(seconds)
    fun setOctaveDivisions(value: Int)
    fun setScaleGuide(nextGuide: ScaleGuide)
    fun displayState(): WaterfallDisplayState
    fun setDisplayState(
        pixels: Double = displayState().pixelsPerSecond,
        pitchScale: Double = displayState().pitchZoomScale,
        pitchPan: Double = displayState().pitchPanSemitones,
        offsetCents: Double = displayState().waterfallOffsetCents
    ): WaterfallDisplayState

    fun setSpeedZoom(
        pixels: Double = displayState().pixelsPerSecond,
        pitchScale: Double = displayState().pitchZoomScale
    ): WaterfallDisplayState

    fun setPitchPan(semitones: Double): WaterfallDisplayState
    fun setWaterfallOffset(cents: Double): WaterfallDisplayState
    fun waterfallGestureHeight(): Float
    fun noteFromRulerTouchPoint(
        x: Float,
        y: Float,
        active: Boolean,
        stickyVisualPitch: Double? = null
    ): WaterfallPreviewNote?

    fun beginManualPreview(pointerId: Int, note: WaterfallPreviewNote)
    fun updateManualPreview(pointerId: Int, note: WaterfallPreviewNote)
    fun releaseManualPreview(pointerId: Int)
    fun clearDynamicEffects()
    fun rulerImpacts(): List<WaterfallRulerImpact> = emptyList()
    fun rulerParticles(): List<WaterfallRulerParticle> = emptyList()
    fun setVolumeGestureFeedback(feedback: WaterfallVolumeGestureFeedback?) = Unit
    fun setRefreshRateHints(
        surfaceFrameRateEnabled: Boolean,
        viewFrameRateEnabled: Boolean
    ) = Unit

    fun requestHighRefreshRate(force: Boolean = false) = Unit
    fun requestHighRefreshFrame(frameTimeNanos: Long) = Unit
    fun setPlaybackActive(active: Boolean) = Unit
    fun setInteractionActive(active: Boolean) = Unit
    fun hasHighRefreshDemand(): Boolean = false

    fun onHostResume() = Unit
    fun onHostPause() = Unit
    fun onHostDestroy() = clearDynamicEffects()
}

data class WaterfallVolumeGestureFeedback(
    val fraction: Float,
    val label: String
)

data class WaterfallRulerImpact(
    val pitch: Double,
    val life: Float,
    val maxLife: Float,
    val velocityRatio: Float,
    val hue: Float
)

data class WaterfallRulerParticle(
    val x: Float,
    val yFromRulerTop: Float,
    val vx: Float,
    val vy: Float,
    val life: Float,
    val maxLife: Float,
    val size: Float,
    val hue: Float,
    val lightness: Float
)
