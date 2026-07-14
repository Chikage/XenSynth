package icu.ringona.xensynth.view

import android.app.ActivityManager
import android.content.Context
import android.graphics.PixelFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.View
import icu.ringona.xensynth.midi.ParsedScore
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * OpenGL ES waterfall backend.
 *
 * Interaction and animation state stay on the main thread. The renderer receives immutable
 * snapshots and owns every GL object on GLSurfaceView's render thread.
 */
class OpenGlWaterfallView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs), WaterfallSurface {
    var onRendererFailure: ((Throwable) -> Unit)? = null

    override val view: View
        get() = this

    override val rendersRulerInternally: Boolean
        get() = false

    private var score: ParsedScore? = null
    private var playheadSeconds = 0.0
    private var pixelsPerSecond = WaterfallMetrics.DEFAULT_PIXELS_PER_SECOND
    private var pitchZoomScale = WaterfallMetrics.PITCH_ZOOM_DEFAULT
    private var pitchPanSemitones = 0.0
    private var waterfallOffsetCents = 0.0
    private var octaveDivisions = 12
    private var playbackActive = false
    private var interactionActive = false
    private var particleCursor = 0
    private var scaleGuide = ScaleGuide.fromResources(context)

    private val particles = mutableListOf<HitParticle>()
    private val keyImpacts = linkedMapOf<Int, KeyImpact>()
    private val manualNotes = linkedMapOf<Int, ManualNoteVisual>()
    private val activeManualNoteIds = linkedMapOf<Int, Int>()
    private var nextManualNoteId = 1
    private var dynamicFramePosted = false
    private var lastDynamicFrameNanos = 0L
    private var hostResumed = false
    private var rendererFailed = false
    private var surfaceFrameRateEnabled = true

    private val framePacer = RenderFramePacer(this)
    private val renderer = OpenGlWaterfallRenderer(
        context = context.applicationContext,
        onFailure = { error ->
            post {
                rendererFailed = true
                Log.e(TAG, "OpenGL waterfall renderer failed", error)
                setBackgroundColor(FALLBACK_BACKGROUND_COLOR)
                onRendererFailure?.invoke(error)
            }
        }
    )

    private val dynamicFrameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        handleDynamicFrame(frameTimeNanos)
    }

    init {
        holder.setFormat(PixelFormat.OPAQUE)
        setEGLContextClientVersion(OPEN_GL_ES_MAJOR_VERSION)
        setEGLConfigChooser(8, 8, 8, 0, 0, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        isClickable = true
        isFocusable = true
        publishScene()
    }

    override fun setScore(nextScore: ParsedScore?) {
        score = nextScore
        playheadSeconds = 0.0
        particleCursor = 0
        clearDynamicEffects()
        publishScene()
    }

    override fun setPlayhead(seconds: Double) {
        if (!seconds.isFinite() || abs(seconds - playheadSeconds) < PLAYHEAD_EPSILON_SECONDS) {
            return
        }
        val previous = playheadSeconds
        playheadSeconds = seconds
        emitHitParticles(previous, seconds)
        publishScene()
    }

    override fun syncPlayhead(seconds: Double) {
        if (!seconds.isFinite()) {
            return
        }
        playheadSeconds = seconds
        particleCursor = findParticleCursor(seconds)
        publishScene()
    }

    override fun setOctaveDivisions(value: Int) {
        val next = value.coerceIn(0, MAX_OCTAVE_DIVISIONS)
        if (octaveDivisions == next) {
            return
        }
        octaveDivisions = next
        publishScene()
    }

    override fun setScaleGuide(nextGuide: ScaleGuide) {
        scaleGuide = nextGuide
        publishScene()
    }

    override fun displayState(): WaterfallDisplayState {
        return WaterfallDisplayState(
            pixelsPerSecond = pixelsPerSecond,
            pitchZoomScale = pitchZoomScale,
            pitchPanSemitones = pitchPanSemitones,
            waterfallOffsetCents = waterfallOffsetCents
        )
    }

    override fun setDisplayState(
        pixels: Double,
        pitchScale: Double,
        pitchPan: Double,
        offsetCents: Double
    ): WaterfallDisplayState {
        pixelsPerSecond = pixels.coerceIn(
            WaterfallMetrics.TIME_ZOOM_MIN,
            WaterfallMetrics.TIME_ZOOM_MAX
        )
        pitchZoomScale = pitchScale.coerceIn(
            WaterfallMetrics.PITCH_ZOOM_MIN,
            WaterfallMetrics.PITCH_ZOOM_MAX
        )
        pitchPanSemitones = WaterfallMetrics.coercePitchPan(pitchZoomScale, pitchPan)
        waterfallOffsetCents = offsetCents.coerceIn(
            -WaterfallMetrics.OFFSET_CENT_RANGE,
            WaterfallMetrics.OFFSET_CENT_RANGE
        )
        publishScene()
        return displayState()
    }

    override fun setSpeedZoom(pixels: Double, pitchScale: Double): WaterfallDisplayState {
        return setDisplayState(
            pixels = pixels,
            pitchScale = pitchScale,
            pitchPan = pitchPanSemitones,
            offsetCents = waterfallOffsetCents
        )
    }

    override fun setPitchPan(semitones: Double): WaterfallDisplayState {
        return setDisplayState(
            pixels = pixelsPerSecond,
            pitchScale = pitchZoomScale,
            pitchPan = semitones,
            offsetCents = waterfallOffsetCents
        )
    }

    override fun setWaterfallOffset(cents: Double): WaterfallDisplayState {
        return setDisplayState(
            pixels = pixelsPerSecond,
            pitchScale = pitchZoomScale,
            pitchPan = pitchPanSemitones,
            offsetCents = cents
        )
    }

    override fun waterfallGestureHeight(): Float {
        return layout().keyboardTop(height.toFloat()).coerceAtLeast(0f)
    }

    override fun noteFromRulerTouchPoint(
        x: Float,
        y: Float,
        active: Boolean,
        stickyVisualPitch: Double?
    ): WaterfallPreviewNote? {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) {
            return null
        }
        val sceneLayout = layout()
        val rulerTop = sceneLayout.keyboardTop(viewHeight)
        val slopDp = if (active) {
            WaterfallMetrics.RULER_ACTIVE_TOUCH_SLOP_DP
        } else {
            WaterfallMetrics.RULER_TOUCH_SLOP_DP
        }
        val slop = slopDp * resources.displayMetrics.density
        if (x < -slop || x > viewWidth + slop || y < rulerTop - slop || y > viewHeight + slop) {
            return null
        }

        val localX = x.coerceIn(0f, viewWidth)
        val localY = y.coerceIn(rulerTop, viewHeight)
        val rawPitch = sceneLayout.xToPitch(localX, viewWidth)
        val targetPitch = quantizePitch(rawPitch) ?: return null
        val snappedPitch = stickyRulerPitch(rawPitch, targetPitch, stickyVisualPitch)
        val pitch = snappedPitch - waterfallOffsetCents / 100.0
        val visualPitch = if (octaveDivisions == 0 && !scaleGuide.isCustom) {
            rawPitch
        } else {
            snappedPitch
        }
        val maxVelocityDepth = sceneLayout.keyboardHeight(viewHeight) *
            WaterfallMetrics.RULER_VELOCITY_MAX_DEPTH
        val depth = (localY - rulerTop).coerceIn(0f, maxVelocityDepth)
        val normalized = if (maxVelocityDepth > 0f) {
            sqrt((depth / maxVelocityDepth).coerceIn(0f, 1f))
        } else {
            0f
        }
        val velocity = (
            WaterfallMetrics.RULER_MIN_VELOCITY +
                normalized * (127 - WaterfallMetrics.RULER_MIN_VELOCITY)
            ).roundToInt().coerceIn(1, 127)
        val midiPitch = pitch.roundToInt().coerceIn(
            WaterfallMetrics.DRAWABLE_MIN_PITCH,
            WaterfallMetrics.DRAWABLE_MAX_PITCH
        )
        return WaterfallPreviewNote(
            pitch = pitch,
            visualPitch = visualPitch,
            midiPitch = midiPitch,
            cents = (pitch - midiPitch) * 100.0,
            velocity = velocity,
            track = 0
        )
    }

    override fun beginManualPreview(pointerId: Int, note: WaterfallPreviewNote) {
        releaseManualPreview(pointerId)
        startManualPreviewSegment(pointerId, note, spawnHit = true)
    }

    override fun updateManualPreview(pointerId: Int, note: WaterfallPreviewNote) {
        val visual = activeManualVisual(pointerId)
        if (visual == null) {
            beginManualPreview(pointerId, note)
            return
        }
        if (abs(visual.visualPitch - note.visualPitch) >= WaterfallMetrics.MANUAL_PITCH_SLOT_EPSILON) {
            val shouldSpawnHit = abs(visual.visualPitch - note.visualPitch) * 100.0 >=
                WaterfallMetrics.PREVIEW_VISUAL_CENTS
            releaseManualPreview(pointerId)
            startManualPreviewSegment(pointerId, note, spawnHit = shouldSpawnHit)
            return
        }
        val oldPitch = visual.visualPitch
        visual.visualPitch = note.visualPitch
        visual.velocity = note.velocity
        visual.track = note.track
        if (abs(oldPitch - note.visualPitch) * 100.0 >= WaterfallMetrics.PREVIEW_VISUAL_CENTS) {
            spawnHitParticles(note)
        }
        scheduleDynamicFrame()
        publishScene()
    }

    override fun releaseManualPreview(pointerId: Int) {
        val visualId = activeManualNoteIds.remove(pointerId) ?: return
        val visual = manualNotes[visualId] ?: return
        if (visual.releasedAtSeconds == null) {
            visual.releasedAtSeconds = nowSeconds()
            scheduleDynamicFrame()
            publishScene()
        }
    }

    override fun clearDynamicEffects() {
        particles.clear()
        keyImpacts.clear()
        manualNotes.clear()
        activeManualNoteIds.clear()
        nextManualNoteId = 1
        dynamicFramePosted = false
        lastDynamicFrameNanos = 0L
        Choreographer.getInstance().removeFrameCallback(dynamicFrameCallback)
        publishScene()
    }

    override fun rulerImpacts(): List<WaterfallRulerImpact> {
        return keyImpacts.values.map { impact ->
            WaterfallRulerImpact(
                pitch = impact.pitch,
                life = impact.life,
                maxLife = impact.maxLife,
                velocityRatio = impact.velocityRatio,
                hue = impact.hue
            )
        }
    }

    override fun rulerParticles(): List<WaterfallRulerParticle> {
        val rulerTop = layout().keyboardTop(height.toFloat())
        return particles.map { particle ->
            WaterfallRulerParticle(
                x = particle.x,
                yFromRulerTop = particle.y - rulerTop,
                vx = particle.vx,
                vy = particle.vy,
                life = particle.life,
                maxLife = particle.maxLife,
                size = particle.size,
                hue = particle.hue,
                lightness = particle.lightness
            )
        }
    }

    override fun setRefreshRateHints(
        surfaceFrameRateEnabled: Boolean,
        viewFrameRateEnabled: Boolean
    ) {
        this.surfaceFrameRateEnabled = surfaceFrameRateEnabled
        requestHighRefreshRate(force = true)
    }

    override fun requestHighRefreshRate(force: Boolean) {
        framePacer.applyPreferredFrameRate(
            holder = holder,
            contentActive = hasHighRefreshDemand(),
            surfaceFrameRateEnabled = surfaceFrameRateEnabled,
            force = force,
            tag = TAG
        )
    }

    override fun requestHighRefreshFrame(frameTimeNanos: Long) {
        requestRenderSafely()
    }

    override fun setPlaybackActive(active: Boolean) {
        if (playbackActive == active) {
            return
        }
        playbackActive = active
        publishScene()
        requestHighRefreshRate()
    }

    override fun setInteractionActive(active: Boolean) {
        if (interactionActive == active) {
            return
        }
        interactionActive = active
        requestRenderSafely()
        requestHighRefreshRate()
    }

    override fun hasHighRefreshDemand(): Boolean {
        return hostResumed && !rendererFailed && (
            playbackActive ||
                interactionActive ||
                dynamicFramePosted ||
                particles.isNotEmpty() ||
                keyImpacts.isNotEmpty() ||
                manualNotes.isNotEmpty()
            )
    }

    override fun onHostResume() {
        if (hostResumed) {
            return
        }
        hostResumed = true
        onResume()
        requestRenderSafely()
        if (particles.isNotEmpty() || keyImpacts.isNotEmpty() || manualNotes.isNotEmpty()) {
            scheduleDynamicFrame()
        }
        requestHighRefreshRate(force = true)
    }

    override fun onHostPause() {
        if (!hostResumed) {
            return
        }
        hostResumed = false
        Choreographer.getInstance().removeFrameCallback(dynamicFrameCallback)
        dynamicFramePosted = false
        lastDynamicFrameNanos = 0L
        particles.clear()
        keyImpacts.clear()
        requestHighRefreshRate(force = true)
        onPause()
    }

    override fun onHostDestroy() {
        clearDynamicEffects()
        renderer.release()
        runCatching {
            queueEvent(renderer::release)
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(dynamicFrameCallback)
        dynamicFramePosted = false
        framePacer.reset()
        super.onDetachedFromWindow()
    }

    private fun startManualPreviewSegment(
        pointerId: Int,
        note: WaterfallPreviewNote,
        spawnHit: Boolean
    ) {
        val visualId = nextManualNoteId++
        manualNotes[visualId] = ManualNoteVisual(
            id = visualId,
            pointerId = pointerId,
            visualPitch = note.visualPitch,
            velocity = note.velocity,
            track = note.track,
            startedAtSeconds = nowSeconds()
        )
        activeManualNoteIds[pointerId] = visualId
        if (spawnHit) {
            spawnHitParticles(note)
        }
        scheduleDynamicFrame()
        publishScene()
    }

    private fun activeManualVisual(pointerId: Int): ManualNoteVisual? {
        return activeManualNoteIds[pointerId]?.let(manualNotes::get)
    }

    private fun publishScene() {
        renderer.updateScene(
            OpenGlWaterfallScene(
                score = score,
                displayState = displayState(),
                playheadSeconds = playheadSeconds,
                playbackActive = playbackActive,
                octaveDivisions = octaveDivisions,
                scaleGuide = scaleGuide,
                manualNotes = manualNotes.values.map { note ->
                    OpenGlManualNote(
                        visualPitch = note.visualPitch,
                        velocity = note.velocity,
                        track = note.track,
                        startedAtSeconds = note.startedAtSeconds,
                        releasedAtSeconds = note.releasedAtSeconds
                    )
                }
            )
        )
        requestRenderSafely()
    }

    private fun requestRenderSafely() {
        if (!rendererFailed) {
            runCatching(::requestRender)
        }
    }

    private fun layout(): WaterfallLayout {
        return WaterfallLayout(
            playheadSeconds = playheadSeconds,
            pixelsPerSecond = pixelsPerSecond,
            pitchZoomScale = pitchZoomScale,
            pitchPanSemitones = pitchPanSemitones,
            waterfallOffsetCents = waterfallOffsetCents,
            density = resources.displayMetrics.density
        )
    }

    private fun quantizePitch(rawPitch: Double): Double? {
        val minPitch = if (scaleGuide.usesFullMidiRange) {
            WaterfallMetrics.DRAWABLE_MIN_PITCH.toDouble()
        } else {
            WaterfallMetrics.MIN_PITCH.toDouble()
        }
        val maxPitch = if (scaleGuide.usesFullMidiRange) {
            WaterfallMetrics.DRAWABLE_MAX_PITCH.toDouble()
        } else {
            WaterfallMetrics.MAX_PITCH.toDouble()
        }
        if (octaveDivisions <= 0 && !scaleGuide.isCustom) {
            return rawPitch.coerceIn(minPitch, maxPitch)
        }
        return scaleGuide.touchPitchForRaw(octaveDivisions, rawPitch)?.coerceIn(minPitch, maxPitch)
    }

    private fun stickyRulerPitch(
        rawPitch: Double,
        snappedPitch: Double,
        stickyVisualPitch: Double?
    ): Double {
        if ((octaveDivisions <= 0 && !scaleGuide.isCustom) || stickyVisualPitch == null) {
            return snappedPitch
        }
        val step = scaleGuide.touchSlotWidth(octaveDivisions, snappedPitch) ?: return snappedPitch
        val hysteresis = min(step * 0.30, WaterfallMetrics.RULER_PITCH_HYSTERESIS_CENTS / 100.0)
        val minPitch = if (scaleGuide.usesFullMidiRange) {
            WaterfallMetrics.DRAWABLE_MIN_PITCH.toDouble()
        } else {
            WaterfallMetrics.MIN_PITCH.toDouble()
        }
        val maxPitch = if (scaleGuide.usesFullMidiRange) {
            WaterfallMetrics.DRAWABLE_MAX_PITCH.toDouble()
        } else {
            WaterfallMetrics.MAX_PITCH.toDouble()
        }
        val previous = stickyVisualPitch.coerceIn(minPitch, maxPitch)
        return if (abs(rawPitch - previous) <= step / 2.0 + hysteresis) {
            previous
        } else {
            snappedPitch
        }
    }

    private fun emitHitParticles(previousPlayhead: Double, currentPlayhead: Double) {
        val notes = score?.notes ?: return
        if (notes.isEmpty()) {
            return
        }
        if (currentPlayhead < previousPlayhead) {
            particleCursor = findParticleCursor(currentPlayhead)
            return
        }
        while (
            particleCursor < notes.size &&
            notes[particleCursor].start < previousPlayhead - WaterfallMetrics.HIT_PARTICLE_CURSOR_EPSILON
        ) {
            particleCursor++
        }
        while (
            particleCursor < notes.size &&
            notes[particleCursor].start <= currentPlayhead + WaterfallMetrics.HIT_PARTICLE_CURSOR_EPSILON
        ) {
            val note = notes[particleCursor]
            spawnHitParticles(
                WaterfallPreviewNote(
                    pitch = note.pitch,
                    visualPitch = layout().renderedPitch(note),
                    midiPitch = note.midiPitch,
                    cents = note.cents,
                    velocity = note.velocity,
                    track = note.track
                ),
                noteDurationSeconds = note.end - note.start
            )
            particleCursor++
        }
    }

    private fun findParticleCursor(playhead: Double): Int {
        val notes = score?.notes ?: return 0
        var low = 0
        var high = notes.size
        val target = playhead - WaterfallMetrics.HIT_PARTICLE_CURSOR_EPSILON
        while (low < high) {
            val middle = (low + high) ushr 1
            if (notes[middle].start < target) {
                low = middle + 1
            } else {
                high = middle
            }
        }
        return low
    }

    private fun spawnHitParticles(
        note: WaterfallPreviewNote,
        noteDurationSeconds: Double? = null
    ) {
        val sceneLayout = layout()
        val viewWidth = width.toFloat().coerceAtLeast(1f)
        val hitY = sceneLayout.keyboardTop(height.toFloat())
        val hue = trackHue(note.track)
        val velocity = note.velocity.coerceIn(1, 127)
        val count = (8 + velocity / 127f * 12).roundToInt()
        val x = sceneLayout.pitchToX(note.visualPitch, viewWidth)
        val spread = max(4f, sceneLayout.noteWidth(viewWidth) * 1.85f)
        repeat(count) { index ->
            val upward = index % 5 != 0
            val baseLife = WaterfallMetrics.HIT_PARTICLE_LIFE_MIN +
                Random.nextFloat() * WaterfallMetrics.HIT_PARTICLE_LIFE_RANDOM_RANGE
            val life = WaterfallMetrics.hitParticleLife(baseLife, noteDurationSeconds)
            val velocityScale = (0.68f + velocity / 127f * 1.02f) *
                WaterfallMetrics.hitParticleMotionScale(life)
            val vx = (Random.nextFloat() - 0.5f) * 170f * velocityScale
            val vy = if (upward) {
                (-120f - Random.nextFloat() * 210f) * velocityScale
            } else {
                (42f + Random.nextFloat() * 118f) * velocityScale
            }
            particles += HitParticle(
                x = x + (Random.nextFloat() - 0.5f) * spread,
                y = hitY + (Random.nextFloat() - 0.5f) * 7f,
                vx = vx,
                vy = vy,
                life = life,
                maxLife = life,
                size = 2.2f + Random.nextFloat() * 3.7f,
                hue = hue,
                lightness = 0.66f + Random.nextFloat() * 0.24f,
                gravityScale = WaterfallMetrics.hitParticleGravityScale(life)
            )
        }
        if (particles.size > WaterfallMetrics.HIT_PARTICLE_MAX) {
            particles.subList(0, particles.size - WaterfallMetrics.HIT_PARTICLE_MAX).clear()
        }
        triggerKeyImpact(note, hue, velocity, noteDurationSeconds)
        scheduleDynamicFrame()
    }

    private fun triggerKeyImpact(
        note: WaterfallPreviewNote,
        hue: Float,
        velocity: Int,
        noteDurationSeconds: Double?
    ) {
        val life = WaterfallMetrics.keyImpactLife(noteDurationSeconds)
        keyImpacts[(note.visualPitch * IMPACT_PITCH_SCALE).roundToInt()] = KeyImpact(
            pitch = note.visualPitch,
            life = life,
            maxLife = life,
            velocityRatio = velocity.coerceIn(0, 127) / 127f,
            hue = hue
        )
    }

    private fun handleDynamicFrame(frameTimeNanos: Long) {
        dynamicFramePosted = false
        val previousFrame = lastDynamicFrameNanos
        lastDynamicFrameNanos = frameTimeNanos
        val deltaSeconds = if (previousFrame == 0L) {
            DEFAULT_FRAME_SECONDS
        } else {
            ((frameTimeNanos - previousFrame) / NANOS_PER_SECOND.toFloat())
                .coerceIn(0f, MAX_FRAME_DELTA_SECONDS)
        }
        updateDynamicEffects(deltaSeconds)
        publishScene()
        if (particles.isNotEmpty() || keyImpacts.isNotEmpty() || manualNotes.isNotEmpty()) {
            scheduleDynamicFrame()
        } else {
            lastDynamicFrameNanos = 0L
            requestHighRefreshRate(force = true)
        }
    }

    private fun updateDynamicEffects(deltaSeconds: Float) {
        if (particles.isNotEmpty()) {
            val damping = PARTICLE_DAMPING.pow(deltaSeconds)
            val iterator = particles.iterator()
            while (iterator.hasNext()) {
                val particle = iterator.next()
                particle.life -= deltaSeconds
                if (particle.life <= 0f) {
                    iterator.remove()
                    continue
                }
                particle.x += particle.vx * deltaSeconds
                particle.y += particle.vy * deltaSeconds
                particle.vy += WaterfallMetrics.HIT_PARTICLE_GRAVITY *
                    particle.gravityScale * deltaSeconds
                particle.vx *= damping
            }
        }
        if (keyImpacts.isNotEmpty()) {
            val iterator = keyImpacts.iterator()
            while (iterator.hasNext()) {
                val impact = iterator.next().value
                impact.life -= deltaSeconds
                if (impact.life <= 0f) {
                    iterator.remove()
                }
            }
        }
        pruneReleasedManualNotes()
    }

    private fun pruneReleasedManualNotes() {
        val now = nowSeconds()
        val keyTop = layout().keyboardTop(height.toFloat())
        val fadeDistance = max(WaterfallMetrics.MANUAL_NOTE_FADE_DISTANCE, height * 0.92f)
        val iterator = manualNotes.iterator()
        while (iterator.hasNext()) {
            val note = iterator.next().value
            val releasedAt = note.releasedAtSeconds ?: continue
            val travel = ((now - releasedAt) * pixelsPerSecond).toFloat()
            val fade = 1f - travel / fadeDistance
            val bottom = keyTop - travel
            if (fade <= 0f || bottom < -WaterfallMetrics.MANUAL_NOTE_OFFSCREEN_MARGIN) {
                iterator.remove()
                if (activeManualNoteIds[note.pointerId] == note.id) {
                    activeManualNoteIds.remove(note.pointerId)
                }
            }
        }
    }

    private fun scheduleDynamicFrame() {
        if (hostResumed && !dynamicFramePosted) {
            dynamicFramePosted = true
            Choreographer.getInstance().postFrameCallback(dynamicFrameCallback)
            requestHighRefreshRate()
        }
    }

    private fun trackHue(track: Int): Float {
        return TRACK_HUES[abs(track) % TRACK_HUES.size]
    }

    private fun nowSeconds(): Double = System.nanoTime() / NANOS_PER_SECOND

    private data class ManualNoteVisual(
        val id: Int,
        val pointerId: Int,
        var visualPitch: Double,
        var velocity: Int,
        var track: Int,
        val startedAtSeconds: Double,
        var releasedAtSeconds: Double? = null
    )

    private data class HitParticle(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var life: Float,
        val maxLife: Float,
        val size: Float,
        val hue: Float,
        val lightness: Float,
        val gravityScale: Float
    )

    private data class KeyImpact(
        val pitch: Double,
        var life: Float,
        val maxLife: Float,
        val velocityRatio: Float,
        val hue: Float
    )

    companion object {
        private const val TAG = "OpenGlWaterfall"
        private const val OPEN_GL_ES_MAJOR_VERSION = 3
        private const val PLAYHEAD_EPSILON_SECONDS = 0.001
        private const val MAX_OCTAVE_DIVISIONS = 240
        private const val DEFAULT_FRAME_SECONDS = 0.016f
        private const val MAX_FRAME_DELTA_SECONDS = 0.08f
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val IMPACT_PITCH_SCALE = 10_000.0
        private const val PARTICLE_DAMPING = 0.18f
        private const val FALLBACK_BACKGROUND_COLOR = 0xFF11100F.toInt()
        private val TRACK_HUES = floatArrayOf(190f, 28f, 132f, 48f, 264f, 158f, 330f, 88f)
        @Volatile private var cachedOpenGlEs3Support: Boolean? = null

        fun isSupported(context: Context): Boolean {
            cachedOpenGlEs3Support?.let { return it }
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val requestedVersion = activityManager?.deviceConfigurationInfo?.reqGlEsVersion ?: 0
            if (requestedVersion < 0x00030000) {
                cachedOpenGlEs3Support = false
                return false
            }
            return synchronized(this) {
                cachedOpenGlEs3Support ?: probeOpenGlEs3Context().also {
                    cachedOpenGlEs3Support = it
                }
            }
        }

        private fun probeOpenGlEs3Context(): Boolean {
            var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
            var context: EGLContext = EGL14.EGL_NO_CONTEXT
            var surface: EGLSurface = EGL14.EGL_NO_SURFACE
            return try {
                display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
                val versions = IntArray(2)
                check(EGL14.eglInitialize(display, versions, 0, versions, 1)) {
                    "eglInitialize failed error=0x${EGL14.eglGetError().toString(16)}"
                }
                val configs = arrayOfNulls<EGLConfig>(1)
                val configCount = IntArray(1)
                val configAttributes = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE,
                    EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                    EGL14.EGL_SURFACE_TYPE,
                    EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RED_SIZE,
                    8,
                    EGL14.EGL_GREEN_SIZE,
                    8,
                    EGL14.EGL_BLUE_SIZE,
                    8,
                    EGL14.EGL_ALPHA_SIZE,
                    0,
                    EGL14.EGL_NONE
                )
                check(
                    EGL14.eglChooseConfig(
                        display,
                        configAttributes,
                        0,
                        configs,
                        0,
                        configs.size,
                        configCount,
                        0
                    ) && configCount[0] > 0
                ) { "No RGB888 OpenGL ES 3 pbuffer config" }
                val config = configs[0] ?: error("EGL returned a null config")
                context = EGL14.eglCreateContext(
                    display,
                    config,
                    EGL14.EGL_NO_CONTEXT,
                    intArrayOf(
                        EGL14.EGL_CONTEXT_CLIENT_VERSION,
                        OPEN_GL_ES_MAJOR_VERSION,
                        EGL14.EGL_NONE
                    ),
                    0
                )
                check(context != EGL14.EGL_NO_CONTEXT) {
                    "eglCreateContext failed error=0x${EGL14.eglGetError().toString(16)}"
                }
                surface = EGL14.eglCreatePbufferSurface(
                    display,
                    config,
                    intArrayOf(
                        EGL14.EGL_WIDTH,
                        1,
                        EGL14.EGL_HEIGHT,
                        1,
                        EGL14.EGL_NONE
                    ),
                    0
                )
                check(surface != EGL14.EGL_NO_SURFACE) {
                    "eglCreatePbufferSurface failed error=0x${EGL14.eglGetError().toString(16)}"
                }
                check(EGL14.eglMakeCurrent(display, surface, surface, context)) {
                    "eglMakeCurrent failed error=0x${EGL14.eglGetError().toString(16)}"
                }
                true
            } catch (error: Throwable) {
                Log.w(TAG, "OpenGL ES 3 preflight failed", error)
                false
            } finally {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        display,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT
                    )
                    if (surface != EGL14.EGL_NO_SURFACE) {
                        EGL14.eglDestroySurface(display, surface)
                    }
                    if (context != EGL14.EGL_NO_CONTEXT) {
                        EGL14.eglDestroyContext(display, context)
                    }
                    EGL14.eglTerminate(display)
                }
                EGL14.eglReleaseThread()
            }
        }
    }
}
