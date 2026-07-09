package icu.ringona.xensynth.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import icu.ringona.xensynth.midi.MidiWaterfallParser
import icu.ringona.xensynth.midi.ParsedScore
import icu.ringona.xensynth.midi.WaterfallNote
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class CanvasWaterfallView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), WaterfallSurface {
    override val view: View
        get() = this

    init {
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    private val gridMinLineSpacingPx = 1.35f
    private val keyboardMinTickSpacingPx = 1.1f

    private var score: ParsedScore? = null
    private var noteIndex: WaterfallVisibleNoteIndex? = null
    private var playheadSeconds = 0.0
    private var pixelsPerSecond = WaterfallMetrics.DEFAULT_PIXELS_PER_SECOND
    private var pitchZoomScale = 1.0
    private var pitchPanSemitones = 0.0
    private var waterfallOffsetCents = 0.0
    private var octaveDivisions = 12
    private var playbackActive = false
    private var interactionActive = false
    private var particleCursor = 0
    private var dynamicFramePosted = false
    private var lastDynamicFrameNanos = 0L
    private var scaleGuide = ScaleGuide.fromResources(context)
    private val sceneBaseColor = Color.TRANSPARENT

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(42, 255, 255, 255)
        strokeWidth = 1f
    }
    private val octavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(96, 255, 255, 255)
        strokeWidth = 1.5f
    }
    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(54, 255, 255, 255)
        strokeWidth = 1f
    }
    private val measureTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        textSize = 11f * resources.displayMetrics.density
    }
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 222, 111)
        strokeWidth = 2f
    }
    private val keyboardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyboardGrainPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyboardEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyboardGlassBounds = RectF()
    private val keyboardBackdropBounds = RectF()
    private val nativeGlassBlur = createNativeFrostedGlassBlur()
    private val legacyGlassBlur = LegacyFrostedGlassBlur()
    private var keyboardBackdropBitmap: Bitmap? = null
    private var keyboardBackdropCanvas: Canvas? = null
    private var lastKeyboardGlassRefreshNanos = 0L
    private var keyboardGlassCacheSignature = Long.MIN_VALUE
    private var keyboardGlassRevision = 0L
    private var keyboardGlassWarmupFramesRemaining = KEYBOARD_GLASS_WARMUP_FRAMES
    private var keyboardGrainBitmap: Bitmap? = null
    private var keyboardGrainShader: BitmapShader? = null
    private val keyboardLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = FrostedGlassStyle.rulerHighlight(110)
        strokeWidth = 1f
    }
    private val keyboardTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = FrostedGlassStyle.rulerHighlight(WaterfallMetrics.C_TICK_ALPHA)
        textAlign = Paint.Align.CENTER
        textSize = 10f * resources.displayMetrics.density
    }
    private val noteStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val manualNotePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val manualNoteStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val particleCorePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val impactPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.BUTT
    }
    private val hsvScratch = FloatArray(3)
    private val manualNoteGradientStops = floatArrayOf(0f, 0.22f, 1f)
    private val particleGradientStops = floatArrayOf(0f, 0.36f, 1f)
    private val noteToneCache = arrayOfNulls<NoteTone>(TRACK_HUES.size * NOTE_TONE_VELOCITY_BUCKETS)

    private val particles = mutableListOf<HitParticle>()
    private val keyImpacts = linkedMapOf<Int, KeyImpact>()
    private val manualNotes = linkedMapOf<Int, ManualNoteVisual>()
    private val activeManualNoteIds = linkedMapOf<Int, Int>()
    private var nextManualNoteId = 1

    private val dynamicFrameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        handleDynamicFrame(frameTimeNanos)
    }

    override fun setScore(nextScore: ParsedScore?) {
        noteIndex = nextScore?.let { WaterfallVisibleNoteIndex(it.notes) }
        score = nextScore
        playheadSeconds = 0.0
        particleCursor = 0
        clearDynamicEffects()
        invalidateKeyboardGlassCache()
        invalidate()
    }

    override fun setPlayhead(seconds: Double) {
        val previous = playheadSeconds
        val next = seconds
        if (abs(next - playheadSeconds) < 0.001) {
            return
        }
        playheadSeconds = next
        emitHitParticles(previous, next)
        if (!playbackActive && !interactionActive) {
            invalidateKeyboardGlassCache()
        }
        postInvalidateOnAnimation()
    }

    override fun syncPlayhead(seconds: Double) {
        playheadSeconds = seconds
        particleCursor = findParticleCursor(playheadSeconds)
        if (!playbackActive && !interactionActive) {
            invalidateKeyboardGlassCache()
        }
        postInvalidateOnAnimation()
    }

    override fun setOctaveDivisions(value: Int) {
        val next = value.coerceIn(0, 240)
        if (octaveDivisions == next) {
            return
        }
        octaveDivisions = next
        invalidateKeyboardGlassCache()
        postInvalidateOnAnimation()
    }

    override fun setScaleGuide(nextGuide: ScaleGuide) {
        scaleGuide = nextGuide
        invalidateKeyboardGlassCache()
        postInvalidateOnAnimation()
    }

    fun loadScaleGuide(input: InputStream): Boolean {
        return runCatching {
            setScaleGuide(ScaleGuide.fromStream(input))
            true
        }.getOrDefault(false)
    }

    fun loadScaleGuide(xml: String): Boolean {
        return runCatching {
            setScaleGuide(ScaleGuide.fromString(xml))
            true
        }.getOrDefault(false)
    }

    fun reloadDefaultScaleGuide() {
        setScaleGuide(ScaleGuide.fromResources(context))
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
        pixelsPerSecond = pixels.coerceIn(WaterfallMetrics.TIME_ZOOM_MIN, WaterfallMetrics.TIME_ZOOM_MAX)
        val nextPitchZoomScale = pitchScale.coerceIn(WaterfallMetrics.PITCH_ZOOM_MIN, WaterfallMetrics.PITCH_ZOOM_MAX)
        pitchZoomScale = nextPitchZoomScale
        pitchPanSemitones = WaterfallMetrics.coercePitchPan(nextPitchZoomScale, pitchPan)
        waterfallOffsetCents = offsetCents.coerceIn(-WaterfallMetrics.OFFSET_CENT_RANGE, WaterfallMetrics.OFFSET_CENT_RANGE)
        invalidateKeyboardGlassCache()
        postInvalidateOnAnimation()
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

    fun isWaterfallArea(y: Float): Boolean {
        return y >= 0f && y <= keyboardTop(height.toFloat())
    }

    override fun waterfallGestureHeight(): Float {
        return keyboardTop(height.toFloat()).coerceAtLeast(0f)
    }

    fun isKeyboardArea(y: Float): Boolean {
        return y >= keyboardTop(height.toFloat()) && y <= height.toFloat()
    }

    override fun noteFromRulerTouchPoint(x: Float, y: Float, active: Boolean, stickyVisualPitch: Double?): WaterfallPreviewNote? {
        val slopDp = if (active) WaterfallMetrics.RULER_ACTIVE_TOUCH_SLOP_DP else WaterfallMetrics.RULER_TOUCH_SLOP_DP
        return noteFromKeyboardPoint(
            x = x,
            y = y,
            touchSlopPx = slopDp * resources.displayMetrics.density,
            stickyVisualPitch = stickyVisualPitch
        )
    }

    fun noteFromKeyboardPoint(
        x: Float,
        y: Float,
        touchSlopPx: Float = 0f,
        stickyVisualPitch: Double? = null
    ): WaterfallPreviewNote? {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) {
            return null
        }
        val keyTop = keyboardTop(viewHeight)
        val slop = touchSlopPx.coerceAtLeast(0f)
        if (
            x < -slop ||
            x > viewWidth + slop ||
            y < keyTop - slop ||
            y > viewHeight + slop
        ) {
            return null
        }
        val localX = x.coerceIn(0f, viewWidth)
        val localY = y.coerceIn(keyTop, viewHeight)
        val keyHeight = keyboardHeight(viewHeight)
        val rawPitch = xToPitch(localX, viewWidth)
        val targetPitch = quantizePitchToEdo(rawPitch) ?: return null
        val snappedPitch = stickyRulerPitch(rawPitch, targetPitch, stickyVisualPitch)
        val pitch = snappedPitch - waterfallOffsetCents / 100.0
        val visualPitch = visualPitchForKeyboard(rawPitch, snappedPitch)
        val maxVelocityDepth = keyHeight * WaterfallMetrics.RULER_VELOCITY_MAX_DEPTH
        val depth = (localY - keyTop).coerceIn(0f, maxVelocityDepth)
        val normalized = if (maxVelocityDepth > 0f) {
            sqrt((depth / maxVelocityDepth).coerceIn(0f, 1f))
        } else {
            0f
        }
        val velocity = (WaterfallMetrics.RULER_MIN_VELOCITY + normalized * (127 - WaterfallMetrics.RULER_MIN_VELOCITY))
            .roundToInt()
            .coerceIn(1, 127)
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

    private fun startManualPreviewSegment(pointerId: Int, note: WaterfallPreviewNote, spawnHit: Boolean) {
        val visualId = nextManualNoteId++
        manualNotes[visualId] = ManualNoteVisual(
            id = visualId,
            pointerId = pointerId,
            pitch = note.pitch,
            visualPitch = note.visualPitch,
            midiPitch = note.midiPitch,
            velocity = note.velocity,
            track = note.track,
            startedAt = nowSeconds()
        )
        activeManualNoteIds[pointerId] = visualId
        if (spawnHit) {
            spawnHitParticles(note)
        }
        scheduleDynamicFrame()
        postInvalidateOnAnimation()
    }

    override fun updateManualPreview(pointerId: Int, note: WaterfallPreviewNote) {
        val visual = activeManualVisual(pointerId)
        if (visual == null) {
            beginManualPreview(pointerId, note)
            return
        }
        if (!sameManualPitchSlot(visual, note)) {
            val shouldSpawnHit = abs(visual.visualPitch - note.visualPitch) * 100.0 >= WaterfallMetrics.PREVIEW_VISUAL_CENTS
            releaseManualPreview(pointerId)
            startManualPreviewSegment(pointerId, note, spawnHit = shouldSpawnHit)
            return
        }
        val oldPitch = visual.visualPitch
        visual.pitch = note.pitch
        visual.visualPitch = note.visualPitch
        visual.midiPitch = note.midiPitch
        visual.velocity = note.velocity
        visual.track = note.track
        if (abs(oldPitch - note.visualPitch) * 100.0 >= WaterfallMetrics.PREVIEW_VISUAL_CENTS) {
            spawnHitParticles(note)
        }
        scheduleDynamicFrame()
        postInvalidateOnAnimation()
    }

    override fun releaseManualPreview(pointerId: Int) {
        val visualId = activeManualNoteIds.remove(pointerId) ?: return
        val visual = manualNotes[visualId] ?: return
        if (visual.releasedAt == null) {
            visual.releasedAt = nowSeconds()
            scheduleDynamicFrame()
            postInvalidateOnAnimation()
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
        invalidateKeyboardGlassCache()
        Choreographer.getInstance().removeFrameCallback(dynamicFrameCallback)
    }

    override fun setPlaybackActive(active: Boolean) {
        if (playbackActive == active) {
            return
        }
        playbackActive = active
        invalidateKeyboardGlassCache()
        postInvalidateOnAnimation()
    }

    override fun setInteractionActive(active: Boolean) {
        if (interactionActive == active) {
            return
        }
        interactionActive = active
        postInvalidateOnAnimation()
    }

    override fun hasHighRefreshDemand(): Boolean {
        return playbackActive ||
            interactionActive ||
            dynamicFramePosted ||
            particles.isNotEmpty() ||
            keyImpacts.isNotEmpty() ||
            manualNotes.isNotEmpty()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        beginKeyboardGlassWarmup()
    }

    override fun requestHighRefreshFrame(frameTimeNanos: Long) {
        postInvalidateOnAnimation()
    }

    override fun rulerImpacts(): List<WaterfallRulerImpact> {
        return keyImpacts.values.map {
            WaterfallRulerImpact(
                pitch = it.pitch,
                life = it.life,
                maxLife = it.maxLife,
                velocityRatio = it.velocityRatio,
                hue = it.hue
            )
        }
    }

    override fun rulerParticles(): List<WaterfallRulerParticle> {
        val rulerTop = keyboardTop(height.toFloat())
        return particles.map {
            WaterfallRulerParticle(
                x = it.x,
                yFromRulerTop = it.y - rulerTop,
                vx = it.vx,
                vy = it.vy,
                life = it.life,
                maxLife = it.maxLife,
                size = it.size,
                hue = it.hue,
                lightness = it.lightness
            )
        }
    }

    fun pitchMoveLimitForCurrentZoom(): Double {
        return pitchMoveLimit()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) {
            return
        }
        val layout = layout()
        val timelinePlayheadSeconds = if (playbackActive) {
            playheadSeconds
        } else {
            score?.let { initialNoteDisplayPlayhead(it.notes) } ?: playheadSeconds
        }
        canvas.drawColor(sceneBaseColor)
        drawSceneContent(canvas, width, height, timelinePlayheadSeconds, layout)
        drawKeyboard(canvas, width, height, timelinePlayheadSeconds, layout)
        drawKeyboardImpacts(canvas, width, height, layout)
        drawParticles(canvas, width, height)
    }

    private fun drawSceneContent(
        canvas: Canvas,
        width: Float,
        height: Float,
        timelinePlayheadSeconds: Double,
        layout: WaterfallLayout
    ) {
        drawPitchGrid(canvas, width, height, layout)
        score?.let { parsed ->
            drawMeasures(canvas, parsed, width, height, timelinePlayheadSeconds, layout)
            drawNotes(canvas, parsed.notes, width, height, timelinePlayheadSeconds, layout)
        }
        drawPlayhead(canvas, width, height, layout)
        drawManualReverseNotes(canvas, width, height, layout)
    }

    private fun prepareKeyboardBackdrop(
        width: Int,
        height: Int,
        sourceTop: Float,
        viewHeight: Float,
        timelinePlayheadSeconds: Double,
        layout: WaterfallLayout
    ): Bitmap? {
        if (width <= 0 || height <= 0) {
            return null
        }
        val current = keyboardBackdropBitmap
        if (current == null || current.width != width || current.height != height || current.isRecycled) {
            current?.recycle()
            keyboardBackdropBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            keyboardBackdropCanvas = Canvas(keyboardBackdropBitmap!!)
        }
        val bitmap = keyboardBackdropBitmap ?: return null
        val backdropCanvas = keyboardBackdropCanvas ?: return null
        bitmap.eraseColor(sceneBaseColor)
        val saveCount = backdropCanvas.save()
        backdropCanvas.translate(0f, -sourceTop)
        drawSceneContent(
            canvas = backdropCanvas,
            width = width.toFloat(),
            height = viewHeight,
            timelinePlayheadSeconds = timelinePlayheadSeconds,
            layout = layout
        )
        backdropCanvas.restoreToCount(saveCount)
        return bitmap
    }

    private fun drawPitchGrid(canvas: Canvas, width: Float, height: Float, layout: WaterfallLayout) {
        val top = layout.keyboardTop(height)
        val minPitch = layout.visiblePitchMin()
        val maxPitch = layout.visiblePitchMax()
        if (!scaleGuide.hasScale(octaveDivisions)) {
            drawLegacyPitchGrid(canvas, width, top, minPitch, maxPitch, layout)
            return
        }
        val minPitchSpacing = pitchSpacingForPixels(minPitch, maxPitch, width, gridMinLineSpacingPx)
        scaleGuide.forEachLineInVisibleRange(octaveDivisions, minPitch, maxPitch, minPitchSpacing) { pitch, ratio, strokeRatio, hasStrokeRatio, isC ->
            val x = layout.pitchToX(pitch, width)
            if (x < -1f || x > width + 1f) {
                return@forEachLineInVisibleRange
            }
            val alpha = (WaterfallMetrics.OCTAVE_LINE_ALPHA * ratio).roundToInt().coerceIn(0, WaterfallMetrics.OCTAVE_LINE_ALPHA)
            val paint = if (isC) octavePaint else gridPaint
            paint.color = Color.argb(alpha, 255, 255, 255)
            paint.strokeWidth = if (hasStrokeRatio) 1.5f * strokeRatio else if (isC) 1.5f else 1f
            drawPixelAlignedVerticalLine(canvas, x, 0f, top, paint)
        }
    }

    private fun drawLegacyPitchGrid(
        canvas: Canvas,
        width: Float,
        top: Float,
        minPitch: Double,
        maxPitch: Double,
        layout: WaterfallLayout
    ) {
        var octavePitch = floor(minPitch / 12.0).toInt() * 12
        while (octavePitch <= maxPitch + 12.0) {
            val x = layout.pitchToX(octavePitch.toDouble(), width)
            if (x < -1f || x > width + 1f) {
                octavePitch += 12
                continue
            }
            octavePaint.color = Color.argb(WaterfallMetrics.OCTAVE_LINE_ALPHA, 255, 255, 255)
            octavePaint.strokeWidth = 1.5f
            drawPixelAlignedVerticalLine(canvas, x, 0f, top, octavePaint)
            octavePitch += 12
        }
        if (octaveDivisions > 0) {
            val step = 12.0 / octaveDivisions
            val minPitchSpacing = pitchSpacingForPixels(minPitch, maxPitch, width, gridMinLineSpacingPx)
            var stepIndex = floor(minPitch / step).toInt()
            var pitch = stepIndex * step
            while (pitch <= maxPitch + step) {
                val octaveOffset = positiveModulo(pitch, 12.0)
                if (octaveOffset <= 0.0001 || 12.0 - octaveOffset <= 0.0001) {
                    stepIndex++
                    pitch += step
                    continue
                }
                val x = layout.pitchToX(pitch, width)
                val visibilityRatio = DenseLineVisibility.ratioForStep(stepIndex, step, minPitchSpacing)
                if (visibilityRatio > 0f && x >= 0f && x <= width) {
                    val alpha = (WaterfallMetrics.GRID_LINE_ALPHA * visibilityRatio)
                        .roundToInt()
                        .coerceIn(0, WaterfallMetrics.GRID_LINE_ALPHA)
                    if (alpha <= 0) {
                        stepIndex++
                        pitch += step
                        continue
                    }
                    gridPaint.color = Color.argb(alpha, 255, 255, 255)
                    gridPaint.strokeWidth = 1f
                    drawPixelAlignedVerticalLine(canvas, x, 0f, top, gridPaint)
                }
                stepIndex++
                pitch += step
            }
        }
    }

    private fun pitchSpacingForPixels(minPitch: Double, maxPitch: Double, width: Float, minPixels: Float): Double {
        if (width <= 0f || minPixels <= 0f || maxPitch <= minPitch) {
            return 0.0
        }
        return (maxPitch - minPitch) * minPixels / width.toDouble().coerceAtLeast(1.0)
    }

    private fun positiveModulo(value: Double, mod: Double): Double {
        return ((value % mod) + mod) % mod
    }

    private fun positiveModulo(value: Int, mod: Int): Int {
        return if (mod == 0) 0 else ((value % mod) + mod) % mod
    }

    private fun drawMeasures(
        canvas: Canvas,
        parsed: ParsedScore,
        width: Float,
        height: Float,
        timelinePlayheadSeconds: Double,
        layout: WaterfallLayout
    ) {
        val top = layout.keyboardTop(height)
        val visibleTimeRange = layout.visibleTimeRange(height, timelinePlayheadSeconds)
        val visibleStart = max(0.0, visibleTimeRange.start)
        val visibleEnd = visibleTimeRange.end
        val startTick = MidiWaterfallParser.secondsToTick(
            visibleStart,
            parsed.tempoMap,
            parsed.ticksPerQuarter
        )
        val endTick = MidiWaterfallParser.secondsToTick(
            visibleEnd,
            parsed.tempoMap,
            parsed.ticksPerQuarter
        )
        parsed.meters.forEachIndexed { index, meter ->
            val stepTicks = MidiWaterfallParser.measureTicks(meter, parsed.ticksPerQuarter)
            val segmentStart = max(0.0, meter.tick.toDouble())
            val segmentEnd = if (index + 1 < parsed.meters.size) {
                parsed.meters[index + 1].tick.toDouble()
            } else {
                endTick + stepTicks
            }
            if (segmentEnd <= segmentStart) {
                return@forEachIndexed
            }
            val firstIndex = max(0, ceil((startTick - segmentStart) / stepTicks).toInt())
            val lastIndex = floor((min(endTick, segmentEnd - 0.0001) - segmentStart) / stepTicks).toInt()
            var measureNumber = 1
            for (i in 0 until index) {
                val previous = parsed.meters[i]
                val previousStep = MidiWaterfallParser.measureTicks(previous, parsed.ticksPerQuarter)
                val previousEnd = parsed.meters.getOrNull(i + 1)?.tick?.toDouble() ?: segmentStart
                measureNumber += ceil((previousEnd - previous.tick) / previousStep).toInt()
            }
            for (localIndex in firstIndex..lastIndex) {
                val tick = segmentStart + localIndex * stepTicks
                val second = MidiWaterfallParser.tickToSeconds(tick.roundToInt().toLong(), parsed.tempoMap, parsed.ticksPerQuarter)
                val y = layout.timeToY(second, height, timelinePlayheadSeconds)
                if (y in 0f..top) {
                    canvas.drawLine(0f, y, width, y, measurePaint)
                    canvas.drawText((measureNumber + localIndex).toString(), 8f, y - 4f, measureTextPaint)
                }
            }
        }
    }

    private fun drawNotes(
        canvas: Canvas,
        notes: List<WaterfallNote>,
        width: Float,
        height: Float,
        timelinePlayheadSeconds: Double,
        layout: WaterfallLayout
    ) {
        val top = layout.keyboardTop(height)
        val visibleTimeRange = layout.visibleTimeRange(height, timelinePlayheadSeconds)
        val visibleStart = visibleTimeRange.start
        val visibleEnd = visibleTimeRange.end
        val minPitch = layout.visiblePitchMin() - 1
        val maxPitch = layout.visiblePitchMax() + 1
        val noteWidth = layout.noteWidth(width)
        val halfNoteWidth = noteWidth / 2f
        fun drawVisibleNote(note: WaterfallNote) {
            val pitch = layout.renderedPitch(note)
            if (pitch < minPitch || pitch > maxPitch) {
                return
            }
            val x = layout.pitchToX(pitch, width)
            val yStart = layout.timeToY(note.start, height, timelinePlayheadSeconds)
            val yEnd = layout.timeToY(note.end, height, timelinePlayheadSeconds)
            val noteTop = min(yStart, yEnd)
            val noteBottom = max(yStart, yEnd)
            val left = x - halfNoteWidth
            val right = x + halfNoteWidth
            var rectTop = max(-8f, noteTop)
            var rectBottom = min(top + 8f, noteBottom)
            if (rectBottom < 0f || rectTop > top) {
                return
            }
            if (rectBottom - rectTop < WaterfallMetrics.NOTE_MIN_HEIGHT_PX) {
                rectBottom = min(top + 8f, rectTop + WaterfallMetrics.NOTE_MIN_HEIGHT_PX)
            }
            val tone = velocityNoteTone(note.track, note.velocity)
            notePaint.color = hsvColor(tone.hue, tone.fillSaturation, tone.value, tone.alpha)
            noteStrokePaint.color = hsvColor(tone.hue, tone.strokeSaturation, tone.strokeValue, tone.strokeAlpha)
            canvas.drawRect(left, rectTop, right, rectBottom, notePaint)
            if (rectBottom - rectTop > 6f && right - left > 2.5f) {
                notePaint.color = hsvColor(tone.hue, 0.70f, tone.highlightValue, tone.highlightAlpha)
                canvas.drawRect(
                    left + 0.6f,
                    rectTop + 0.6f,
                    right - 0.6f,
                    min(rectBottom, rectTop + 2.0f),
                    notePaint
                )
            }
            canvas.drawRect(left, rectTop, right, rectBottom, noteStrokePaint)
        }

        val visibleNoteIndices = noteIndex?.visibleNoteIndices(visibleStart, visibleEnd)
        if (visibleNoteIndices != null) {
            val noteCount = notes.size
            for (index in visibleNoteIndices) {
                if (index >= 0 && index < noteCount) {
                    drawVisibleNote(notes[index])
                }
            }
            return
        }

        for (note in notes) {
            if (note.start > visibleEnd) {
                break
            }
            if (note.end >= visibleStart) {
                drawVisibleNote(note)
            }
        }
    }

    private fun initialNoteDisplayPlayhead(notes: List<WaterfallNote>): Double {
        return layout().initialNoteDisplayPlayhead(notes)
    }

    private fun drawPlayhead(canvas: Canvas, width: Float, height: Float, layout: WaterfallLayout) {
        val y = layout.timeToY(playheadSeconds, height)
        canvas.drawLine(0f, y, width, y, playheadPaint)
    }

    private fun drawKeyboard(
        canvas: Canvas,
        width: Float,
        height: Float,
        timelinePlayheadSeconds: Double,
        layout: WaterfallLayout
    ) {
        val top = layout.keyboardTop(height)
        val keyHeight = layout.keyboardHeight(height)
        val bottom = top + keyHeight
        drawKeyboardGlass(canvas, width, top, height, timelinePlayheadSeconds, layout)

        if (!scaleGuide.hasScale(octaveDivisions)) {
            drawLegacyKeyboard(canvas, width, top, bottom, keyHeight, layout)
            return
        }
        val minPitch = layout.visiblePitchMin()
        val maxPitch = layout.visiblePitchMax()
        val minPitchSpacing = pitchSpacingForPixels(minPitch, maxPitch, width, keyboardMinTickSpacingPx)
        scaleGuide.forEachLineInVisibleRange(octaveDivisions, minPitch, maxPitch, minPitchSpacing) { pitch, ratio, strokeRatio, hasStrokeRatio, isC ->
            val x = layout.pitchToX(pitch, width)
            if (x < -1f || x > width + 1f) {
                return@forEachLineInVisibleRange
            }
            val label = scaleGuide.labelForPitch(pitch, isC)
            val length = keyHeight * WaterfallMetrics.C_TICK_HEIGHT_RATIO * ratio
            val tickLength = if (label != null) {
                min(length, keyHeight - WaterfallMetrics.C4_LABEL_RESERVED_HEIGHT_DP * resources.displayMetrics.density)
            } else {
                length
            }
            keyboardLinePaint.color = FrostedGlassStyle.rulerHighlight(
                (WaterfallMetrics.C_TICK_ALPHA * ratio).roundToInt().coerceIn(0, WaterfallMetrics.C_TICK_ALPHA)
            )
            keyboardLinePaint.strokeWidth = if (hasStrokeRatio) 1.4f * strokeRatio else if (isC) 1.4f else 1f
            val alignedX = drawPixelAlignedVerticalLine(canvas, x, top, top + tickLength, keyboardLinePaint)
            if (label != null) {
                canvas.drawText(
                    label,
                    alignedX,
                    bottom - WaterfallMetrics.C4_LABEL_BOTTOM_PADDING_DP * resources.displayMetrics.density,
                    keyboardTextPaint
                )
            }
        }
    }

    private fun drawLegacyKeyboard(
        canvas: Canvas,
        width: Float,
        top: Float,
        bottom: Float,
        keyHeight: Float,
        layout: WaterfallLayout
    ) {
        val firstHalfStep = floor(layout.visiblePitchMin() * 2.0).toInt()
        val lastHalfStep = ceil(layout.visiblePitchMax() * 2.0).toInt()
        for (halfStep in firstHalfStep..lastHalfStep) {
            val pitch = halfStep / 2.0
            val x = layout.pitchToX(pitch, width)
            if (x < -1f || x > width + 1f) {
                continue
            }
            val tick = keyboardTick(pitch, keyHeight)
            if (!tick.isVisible) {
                continue
            }
            val label = scaleGuide.labelForPitch(pitch, tick.isC)
            val tickLength = if (label != null) {
                min(tick.length, keyHeight - WaterfallMetrics.C4_LABEL_RESERVED_HEIGHT_DP * resources.displayMetrics.density)
            } else {
                tick.length
            }
            keyboardLinePaint.color = FrostedGlassStyle.rulerHighlight(tick.alpha)
            keyboardLinePaint.strokeWidth = tick.strokeWidth
            val alignedX = drawPixelAlignedVerticalLine(canvas, x, top, top + tickLength, keyboardLinePaint)
            if (label != null) {
                canvas.drawText(
                    label,
                    alignedX,
                    bottom - WaterfallMetrics.C4_LABEL_BOTTOM_PADDING_DP * resources.displayMetrics.density,
                    keyboardTextPaint
                )
            }
        }
    }

    private fun drawKeyboardGlass(
        canvas: Canvas,
        width: Float,
        top: Float,
        bottom: Float,
        timelinePlayheadSeconds: Double,
        layout: WaterfallLayout
    ) {
        keyboardGlassBounds.set(0f, top, width, bottom)
        val backdropHeight = max(1, ceil(bottom - top).toInt())
        val backdropWidth = width.roundToInt().coerceAtLeast(1)
        val now = System.nanoTime()
        val signature = keyboardGlassSignature(
            width = backdropWidth,
            height = backdropHeight,
            top = top,
            bottom = bottom,
            layout = layout
        )
        val refreshContent = shouldRefreshKeyboardGlass(now, signature)
        val backdrop = if (refreshContent) {
            prepareKeyboardBackdrop(
                width = backdropWidth,
                height = backdropHeight,
                sourceTop = top,
                viewHeight = bottom,
                timelinePlayheadSeconds = timelinePlayheadSeconds,
                layout = layout
            )
        } else {
            keyboardBackdropBitmap
        }
        keyboardBackdropBounds.set(0f, 0f, width, backdropHeight.toFloat())
        val saveCount = canvas.save()
        canvas.translate(0f, top)
        val blurred = (nativeGlassBlur?.drawCachedBlurredBitmapRegion(
            canvas,
            backdrop,
            keyboardBackdropBounds,
            FrostedGlassStyle.RULER_BLUR_RADIUS,
            refreshContent
        ) == true) || legacyGlassBlur.drawCachedBlurredBitmapRegion(
            canvas,
            backdrop,
            keyboardBackdropBounds,
            FrostedGlassStyle.RULER_BLUR_RADIUS,
            refreshContent
        )
        if (!blurred && backdrop != null && !backdrop.isRecycled) {
            canvas.drawBitmap(backdrop, 0f, 0f, null)
        }
        canvas.restoreToCount(saveCount)
        if (refreshContent && backdrop != null && !backdrop.isRecycled) {
            keyboardGlassCacheSignature = signature
            lastKeyboardGlassRefreshNanos = now
            completeKeyboardGlassWarmupFrame()
        }
        drawKeyboardFrostVeil(canvas, keyboardGlassBounds)
        canvas.drawDarkFrostedPanel(
            bounds = keyboardGlassBounds,
            paint = keyboardPaint,
            topAlpha = FrostedGlassStyle.RULER_TOP_ALPHA,
            bottomAlpha = FrostedGlassStyle.RULER_BOTTOM_ALPHA,
            hairlineAlpha = FrostedGlassStyle.RULER_HAIRLINE_ALPHA,
            shadowAlpha = FrostedGlassStyle.RULER_SHADOW_ALPHA,
            topHighlightAlpha = 36,
            highlightColor = FrostedGlassStyle::rulerHighlight
        )
        drawKeyboardGlassSheen(canvas, keyboardGlassBounds)
        drawKeyboardFineGrain(canvas, keyboardGlassBounds)
        drawKeyboardGlassEdges(canvas, keyboardGlassBounds)
    }

    private fun shouldRefreshKeyboardGlass(now: Long, signature: Long): Boolean {
        if (signature != keyboardGlassCacheSignature || keyboardBackdropBitmap == null) {
            return true
        }
        if (keyboardGlassWarmupFramesRemaining > 0) {
            return true
        }
        if (!hasHighRefreshDemand()) {
            return false
        }
        return lastKeyboardGlassRefreshNanos <= 0L ||
            now - lastKeyboardGlassRefreshNanos >= KEYBOARD_GLASS_ACTIVE_REFRESH_NANOS
    }

    private fun keyboardGlassSignature(
        width: Int,
        height: Int,
        top: Float,
        bottom: Float,
        layout: WaterfallLayout
    ): Long {
        var result = 17L
        result = 31L * result + width
        result = 31L * result + height
        result = 31L * result + top.roundToInt()
        result = 31L * result + bottom.roundToInt()
        result = 31L * result + octaveDivisions
        result = 31L * result + keyboardGlassRevision
        result = 31L * result + signaturePart(layout.pixelsPerSecond)
        result = 31L * result + signaturePart(layout.pitchZoomScale)
        result = 31L * result + signaturePart(layout.pitchPanSemitones)
        result = 31L * result + signaturePart(layout.waterfallOffsetCents)
        result = 31L * result + (score?.let { System.identityHashCode(it) } ?: 0)
        return result
    }

    private fun signaturePart(value: Double): Long {
        return (value * 1000.0).roundToInt().toLong()
    }

    private fun invalidateKeyboardGlassCache() {
        keyboardGlassRevision++
        keyboardGlassCacheSignature = Long.MIN_VALUE
        lastKeyboardGlassRefreshNanos = 0L
        beginKeyboardGlassWarmup()
        nativeGlassBlur?.resetCache()
        legacyGlassBlur.resetCache()
    }

    private fun beginKeyboardGlassWarmup(frames: Int = KEYBOARD_GLASS_WARMUP_FRAMES) {
        keyboardGlassWarmupFramesRemaining = max(keyboardGlassWarmupFramesRemaining, frames)
        postInvalidateOnAnimation()
    }

    private fun completeKeyboardGlassWarmupFrame() {
        if (keyboardGlassWarmupFramesRemaining <= 0) {
            return
        }
        keyboardGlassWarmupFramesRemaining--
        if (keyboardGlassWarmupFramesRemaining > 0) {
            postInvalidateOnAnimation()
        }
    }

    private fun drawKeyboardFrostVeil(canvas: Canvas, area: RectF) {
        if (area.height() <= 0f) {
            return
        }
        keyboardPaint.shader = LinearGradient(
            0f,
            area.top,
            0f,
            area.bottom,
            intArrayOf(
                FrostedGlassStyle.coolHighlight(74),
                FrostedGlassStyle.rulerHighlight(38),
                FrostedGlassStyle.coolHighlight(18),
                FrostedGlassStyle.shadow(22)
            ),
            floatArrayOf(0f, 0.2f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(area, keyboardPaint)
        keyboardPaint.shader = null
    }

    private fun drawKeyboardGlassSheen(canvas: Canvas, area: RectF) {
        if (area.height() <= 0f) {
            return
        }
        keyboardPaint.shader = LinearGradient(
            area.left,
            area.top,
            area.right,
            area.bottom,
            intArrayOf(
                FrostedGlassStyle.rulerHighlight(96),
                FrostedGlassStyle.coolHighlight(42),
                Color.TRANSPARENT,
                FrostedGlassStyle.shadow(30)
            ),
            floatArrayOf(0f, 0.18f, 0.58f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(area, keyboardPaint)
        keyboardPaint.shader = null
    }

    private fun drawKeyboardFineGrain(canvas: Canvas, area: RectF) {
        keyboardGrainPaint.shader = keyboardGrainShader()
        canvas.drawRect(area, keyboardGrainPaint)
        keyboardGrainPaint.shader = null
    }

    private fun drawKeyboardGlassEdges(canvas: Canvas, area: RectF) {
        if (area.height() <= 1f) {
            return
        }
        val density = resources.displayMetrics.density
        val topDepth = min(area.height(), max(3.5f, 1.25f * density))
        val bottomDepth = min(area.height(), max(8f, 3.0f * density))
        keyboardEdgePaint.shader = LinearGradient(
            0f,
            area.top,
            0f,
            area.top + topDepth,
            intArrayOf(
                FrostedGlassStyle.rulerHighlight(118),
                FrostedGlassStyle.coolHighlight(28),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.30f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(area.left, area.top, area.right, area.top + topDepth, keyboardEdgePaint)
        keyboardEdgePaint.shader = null
        val depthTop = area.bottom - bottomDepth
        keyboardEdgePaint.shader = LinearGradient(
            0f,
            depthTop,
            0f,
            area.bottom,
            intArrayOf(
                Color.TRANSPARENT,
                FrostedGlassStyle.rulerHighlight(34),
                FrostedGlassStyle.shadow(86)
            ),
            floatArrayOf(0f, 0.34f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(area.left, depthTop, area.right, area.bottom, keyboardEdgePaint)
        keyboardEdgePaint.shader = null
        keyboardEdgePaint.color = FrostedGlassStyle.rulerHighlight(FrostedGlassStyle.RULER_HAIRLINE_ALPHA)
        canvas.drawRect(area.left, area.top, area.right, area.top + 1f, keyboardEdgePaint)
        keyboardEdgePaint.color = FrostedGlassStyle.shadow(72)
        canvas.drawRect(area.left, area.bottom - 1f, area.right, area.bottom, keyboardEdgePaint)
    }

    private fun keyboardGrainShader(): BitmapShader {
        keyboardGrainShader?.let { return it }
        val bitmap = createKeyboardGrainBitmap()
        keyboardGrainBitmap = bitmap
        return BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).also {
            keyboardGrainShader = it
        }
    }

    private fun createKeyboardGrainBitmap(): Bitmap {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val noise = (x * 67 + y * 139 + x * y * 11 + ((x * 37) xor (y * 53))) and 0xFF
                val bright = (noise and 1) == 0
                val alpha = if (bright) {
                    6 + noise % 12
                } else {
                    2 + noise % 7
                }
                val channel = if (bright) 255 else 0
                bitmap.setPixel(x, y, Color.argb(alpha, channel, channel, channel))
            }
        }
        return bitmap
    }

    override fun onDetachedFromWindow() {
        legacyGlassBlur.release()
        keyboardBackdropBitmap?.recycle()
        keyboardBackdropBitmap = null
        keyboardBackdropCanvas = null
        keyboardGlassWarmupFramesRemaining = 0
        nativeGlassBlur?.resetCache()
        keyboardGrainBitmap?.recycle()
        keyboardGrainBitmap = null
        keyboardGrainShader = null
        super.onDetachedFromWindow()
    }

    private fun drawManualReverseNotes(canvas: Canvas, width: Float, height: Float, layout: WaterfallLayout) {
        if (manualNotes.isEmpty()) {
            return
        }
        val now = nowSeconds()
        val keyTop = layout.keyboardTop(height)
        val fadeDistance = max(WaterfallMetrics.MANUAL_NOTE_FADE_DISTANCE, height * 0.92f)
        val noteWidth = max(WaterfallMetrics.NOTE_MIN_WIDTH_PX + 1f, min(WaterfallMetrics.NOTE_MAX_WIDTH_PX + 2f, width / WaterfallMetrics.NOTE_RANGE.toFloat() * 0.22f))
        val activeFrames = mutableListOf<ManualNoteFrame>()
        val releasedFrames = mutableListOf<ManualNoteFrame>()
        val iterator = manualNotes.iterator()
        while (iterator.hasNext()) {
            val note = iterator.next().value
            val releasedAt = note.releasedAt ?: now
            val heldSeconds = max(WaterfallMetrics.MANUAL_NOTE_MIN_SECONDS, releasedAt - note.startedAt)
            val releaseAge = if (note.releasedAt == null) 0.0 else now - note.releasedAt!!
            val travel = (releaseAge * pixelsPerSecond).toFloat()
            val fade = if (note.releasedAt == null) {
                1f
            } else {
                (1f - travel / fadeDistance).coerceIn(0f, 1f)
            }
            val bottom = keyTop - travel
            val top = bottom - max(WaterfallMetrics.MANUAL_NOTE_MIN_HEIGHT, (heldSeconds * pixelsPerSecond).toFloat())
            if (fade <= 0f || bottom < -WaterfallMetrics.MANUAL_NOTE_OFFSCREEN_MARGIN) {
                iterator.remove()
                if (activeManualNoteIds[note.pointerId] == note.id) {
                    activeManualNoteIds.remove(note.pointerId)
                }
                continue
            }
            if (top > height || bottom < -12f) {
                continue
            }
            val frame = ManualNoteFrame(
                note = note,
                fade = fade,
                clippedTop = max(-8f, top),
                clippedBottom = min(height + 8f, bottom)
            )
            if (note.releasedAt == null) {
                activeFrames += frame
            } else {
                releasedFrames += frame
            }
        }
        activeFrames.forEach { drawManualReverseNoteFrame(canvas, width, noteWidth, it, layout) }
        releasedFrames.forEach { drawManualReverseNoteFrame(canvas, width, noteWidth, it, layout) }
    }

    private fun drawManualReverseNoteFrame(
        canvas: Canvas,
        width: Float,
        noteWidth: Float,
        frame: ManualNoteFrame,
        layout: WaterfallLayout
    ) {
        val note = frame.note
        val fade = frame.fade
        val clippedTop = frame.clippedTop
        val clippedBottom = frame.clippedBottom
        val x = layout.pitchToX(note.visualPitch, width)
        val tone = velocityNoteTone(note.track, note.velocity)
        val alpha = tone.alpha * (0.38f + fade * 0.62f)
        manualNotePaint.shader = LinearGradient(
            0f,
            clippedTop,
            0f,
            clippedBottom,
            intArrayOf(
                hsvColor(tone.hue, 0.96f, tone.highlightValue, alpha * 0.95f),
                hsvColor(tone.hue, tone.fillSaturation, tone.value, alpha),
                hsvColor(tone.hue, 0.82f, max(0.36f, tone.value - 0.26f), alpha * 0.34f)
            ),
            manualNoteGradientStops,
            Shader.TileMode.CLAMP
        )
        manualNoteStrokePaint.color = hsvColor(
            tone.hue,
            tone.strokeSaturation,
            tone.strokeValue,
            tone.strokeAlpha * (0.48f + fade * 0.52f)
        )
        val left = x - noteWidth / 2f
        val right = x + noteWidth / 2f
        canvas.drawRect(left, clippedTop, right, clippedBottom, manualNotePaint)
        canvas.drawRect(left, clippedTop, right, clippedBottom, manualNoteStrokePaint)
        manualNotePaint.shader = null
    }

    private fun drawKeyboardImpacts(canvas: Canvas, width: Float, height: Float, layout: WaterfallLayout) {
        if (keyImpacts.isEmpty()) {
            return
        }
        val keyHeight = layout.keyboardHeight(height)
        val top = layout.keyboardTop(height)
        val noteWidth = layout.noteWidth(width)
        keyImpacts.values.forEach { entry ->
            val progress = (entry.life / entry.maxLife).coerceIn(0f, 1f)
            val velocityRatio = entry.velocityRatio.coerceIn(0f, 1f)
            val amount = sin(progress * Math.PI).toFloat() * velocityRatio
            val fade = progress.pow(0.72f)
            val tick = scaleGuide.impactTickForPitch(octaveDivisions, entry.pitch, keyHeight)
            val maxAmplitude = max(0f, keyHeight - 2f - tick.length) * velocityRatio
            val tickLength = min(keyHeight - 2f, tick.length + maxAmplitude * amount)
            val yOffset = -min(4f, keyHeight * 0.08f) * amount
            val x = layout.pitchToX(entry.pitch, width)
            impactPaint.color = hsvColor(entry.hue, 0.94f, 0.98f, 0.54f + fade * (0.30f + velocityRatio * 0.14f))
            impactPaint.strokeWidth = max(
                noteWidth,
                tick.strokeWidth + WaterfallMetrics.IMPACT_EXTRA_WIDTH * (0.35f + amount)
            )
            canvas.drawLine(x, top + yOffset, x, top + yOffset + tickLength, impactPaint)
        }
    }

    private fun drawParticles(canvas: Canvas, width: Float, height: Float) {
        if (particles.isEmpty()) {
            return
        }
        val usesAddBlend = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        if (usesAddBlend) {
            particlePaint.blendMode = BlendMode.PLUS
            particleCorePaint.blendMode = BlendMode.PLUS
        }
        particles.forEach { particle ->
            if (particle.x < -12f || particle.x > width + 12f || particle.y < -24f || particle.y > height + 24f) {
                return@forEach
            }
            val lifeRatio = (particle.life / particle.maxLife).coerceIn(0f, 1f)
            val alpha = lifeRatio.pow(1.05f)
            val size = particle.size * (1f + (1f - alpha) * 0.92f)
            val glowSize = size * 2.45f
            particlePaint.shader = null
            particlePaint.style = Paint.Style.STROKE
            particlePaint.strokeCap = Paint.Cap.ROUND
            particlePaint.strokeWidth = max(1.5f, size * 0.95f)
            particlePaint.color = hsvColor(particle.hue, 0.94f, min(0.95f, particle.lightness + 0.15f), alpha * 0.68f)
            canvas.drawLine(
                particle.x,
                particle.y,
                particle.x - particle.vx * 0.024f,
                particle.y - particle.vy * 0.024f,
                particlePaint
            )
            particlePaint.style = Paint.Style.FILL
            particlePaint.shader = RadialGradient(
                particle.x,
                particle.y,
                glowSize,
                intArrayOf(
                    hsvColor(particle.hue, 0.98f, 1.0f, alpha * 0.72f),
                    hsvColor(particle.hue, 0.96f, 0.78f, alpha * 0.34f),
                    hsvColor(particle.hue, 0.96f, 0.72f, 0f)
                ),
                particleGradientStops,
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(particle.x, particle.y, glowSize, particlePaint)
            particlePaint.shader = null
            particleCorePaint.style = Paint.Style.FILL
            particleCorePaint.color = hsvColor(particle.hue, 0.64f, 1.0f, alpha * 0.92f)
            canvas.drawCircle(particle.x, particle.y, max(1.2f, size * 0.54f), particleCorePaint)
        }
        if (usesAddBlend) {
            particlePaint.blendMode = null
            particleCorePaint.blendMode = null
        }
    }

    private fun emitHitParticles(previousPlayhead: Double, currentPlayhead: Double) {
        val parsed = score ?: return
        val notes = parsed.notes
        if (notes.isEmpty()) {
            return
        }
        if (currentPlayhead < previousPlayhead) {
            particleCursor = findParticleCursor(currentPlayhead)
            return
        }
        while (particleCursor < notes.size && notes[particleCursor].start < previousPlayhead - WaterfallMetrics.HIT_PARTICLE_CURSOR_EPSILON) {
            particleCursor++
        }
        while (particleCursor < notes.size && notes[particleCursor].start <= currentPlayhead + WaterfallMetrics.HIT_PARTICLE_CURSOR_EPSILON) {
            val note = notes[particleCursor]
            val preview = WaterfallPreviewNote(
                pitch = note.pitch,
                visualPitch = renderedPitch(note),
                midiPitch = note.midiPitch,
                cents = note.cents,
                velocity = note.velocity,
                track = note.track
            )
            spawnHitParticles(preview, note.end - note.start)
            particleCursor++
        }
    }

    private fun findParticleCursor(playhead: Double): Int {
        val notes = score?.notes ?: return 0
        var lo = 0
        var hi = notes.size
        val target = playhead - WaterfallMetrics.HIT_PARTICLE_CURSOR_EPSILON
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (notes[mid].start < target) {
                lo = mid + 1
            } else {
                hi = mid
            }
        }
        return lo
    }

    private fun spawnHitParticles(note: WaterfallPreviewNote, noteDurationSeconds: Double? = null) {
        val viewWidth = width.toFloat().coerceAtLeast(1f)
        val hitY = keyboardTop(height.toFloat())
        val hue = trackHue(note.track)
        val velocity = note.velocity.coerceIn(1, 127)
        val count = (8 + velocity / 127f * 12).roundToInt()
        val x = pitchToX(note.visualPitch, viewWidth)
        val spread = max(4f, noteWidth() * 1.85f)
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

    private fun triggerKeyImpact(note: WaterfallPreviewNote, hue: Float, velocity: Int, noteDurationSeconds: Double?) {
        val life = WaterfallMetrics.keyImpactLife(noteDurationSeconds)
        keyImpacts[impactKeyForPitch(note.visualPitch)] = KeyImpact(
            pitch = note.visualPitch,
            life = life,
            maxLife = life,
            velocityRatio = normalizedVelocity(velocity),
            hue = hue
        )
    }

    private fun updateDynamicEffects(dt: Float) {
        if (particles.isNotEmpty()) {
            val damping = 0.18f.pow(dt)
            val iterator = particles.iterator()
            while (iterator.hasNext()) {
                val particle = iterator.next()
                particle.life -= dt
                if (particle.life <= 0f) {
                    iterator.remove()
                    continue
                }
                particle.x += particle.vx * dt
                particle.y += particle.vy * dt
                particle.vy += WaterfallMetrics.HIT_PARTICLE_GRAVITY * particle.gravityScale * dt
                particle.vx *= damping
            }
        }
        if (keyImpacts.isNotEmpty()) {
            val iterator = keyImpacts.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next().value
                entry.life -= dt
                if (entry.life <= 0f) {
                    iterator.remove()
                }
            }
        }
    }

    private fun handleDynamicFrame(frameTimeNanos: Long) {
        dynamicFramePosted = false
        val hadDynamicVisuals = particles.isNotEmpty() || keyImpacts.isNotEmpty() || manualNotes.isNotEmpty()
        val lastFrame = lastDynamicFrameNanos
        lastDynamicFrameNanos = frameTimeNanos
        val dt = if (lastFrame == 0L) {
            0.016f
        } else {
            ((frameTimeNanos - lastFrame) / 1_000_000_000.0f).coerceIn(0f, 0.08f)
        }
        updateDynamicEffects(dt)
        val hasDynamicVisuals = particles.isNotEmpty() || keyImpacts.isNotEmpty() || manualNotes.isNotEmpty()
        if (hadDynamicVisuals && !hasDynamicVisuals) {
            invalidateKeyboardGlassCache()
        }
        postInvalidateOnAnimation()
        if (hasDynamicVisuals) {
            scheduleDynamicFrame()
        } else {
            lastDynamicFrameNanos = 0L
        }
    }

    private fun scheduleDynamicFrame() {
        if (!dynamicFramePosted) {
            dynamicFramePosted = true
            Choreographer.getInstance().postFrameCallback(dynamicFrameCallback)
        }
    }

    private fun keyboardTick(pitch: Double, keyHeight: Float): ScaleGuide.KeyboardTickStyle {
        return scaleGuide.tickForPitch(octaveDivisions, pitch, keyHeight)
    }

    private fun timeToY(time: Double, height: Float, playhead: Double = playheadSeconds): Float {
        return layout().timeToY(time, height, playhead)
    }

    private fun keyboardHeight(height: Float): Float {
        return layout().keyboardHeight(height)
    }

    private fun keyboardTop(height: Float): Float {
        return layout().keyboardTop(height)
    }

    private fun renderedPitch(note: WaterfallNote): Double {
        return layout().renderedPitch(note)
    }

    private fun xToPitch(x: Float, width: Float): Double {
        return layout().xToPitch(x, width)
    }

    private fun quantizePitchToEdo(rawPitch: Double): Double? {
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
        return scaleGuide
            .touchPitchForRaw(octaveDivisions, rawPitch)
            ?.coerceIn(minPitch, maxPitch)
    }

    private fun stickyRulerPitch(rawPitch: Double, snappedPitch: Double, stickyVisualPitch: Double?): Double {
        if ((octaveDivisions <= 0 && !scaleGuide.isCustom) || stickyVisualPitch == null) {
            return snappedPitch
        }
        val step = scaleGuide.touchSlotWidth(octaveDivisions, snappedPitch) ?: return snappedPitch
        val hysteresis = min(step * 0.30, WaterfallMetrics.RULER_PITCH_HYSTERESIS_CENTS / 100.0)
        val previous = stickyVisualPitch.coerceIn(WaterfallMetrics.MIN_PITCH.toDouble(), WaterfallMetrics.MAX_PITCH.toDouble())
        return if (abs(rawPitch - previous) <= step / 2.0 + hysteresis) {
            previous
        } else {
            snappedPitch
        }
    }

    private fun visualPitchForKeyboard(rawPitch: Double, snappedPitch: Double): Double {
        return if (octaveDivisions == 0 && !scaleGuide.isCustom) rawPitch else snappedPitch
    }

    private fun activeManualVisual(pointerId: Int): ManualNoteVisual? {
        val visualId = activeManualNoteIds[pointerId] ?: return null
        return manualNotes[visualId]
    }

    private fun sameManualPitchSlot(visual: ManualNoteVisual, note: WaterfallPreviewNote): Boolean {
        return abs(visual.visualPitch - note.visualPitch) < WaterfallMetrics.MANUAL_PITCH_SLOT_EPSILON
    }

    private fun noteWidth(): Float {
        return layout().noteWidth(width.toFloat())
    }

    private fun normalizedVelocity(velocity: Int): Float {
        return (velocity.coerceIn(0, 127) / 127f)
    }

    private fun velocityNoteTone(track: Int, velocity: Int): NoteTone {
        val hueIndex = trackHueIndex(track)
        val velocityBucket = velocity.coerceIn(0, 127)
        val cacheIndex = hueIndex * NOTE_TONE_VELOCITY_BUCKETS + velocityBucket
        noteToneCache[cacheIndex]?.let { return it }
        val ratio = normalizedVelocity(velocityBucket)
        val emphasis = ratio.pow(0.72f)
        val value = 0.58f + emphasis * 0.40f
        val lowContrastOutline = value < 0.74f
        return NoteTone(
            hue = TRACK_HUES[hueIndex],
            fillSaturation = 0.74f + ratio * 0.12f,
            value = value,
            alpha = 0.76f + ratio * 0.20f,
            strokeSaturation = 0.82f + ratio * 0.10f,
            strokeValue = if (lowContrastOutline) min(0.98f, value + 0.24f) else max(0.30f, value - 0.52f),
            strokeAlpha = if (lowContrastOutline) 0.62f else 0.74f,
            highlightValue = min(1.0f, value + 0.18f),
            highlightAlpha = if (lowContrastOutline) 0.22f else 0.14f
        ).also { noteToneCache[cacheIndex] = it }
    }

    private fun pitchToX(pitch: Double, width: Float): Float {
        return layout().pitchToX(pitch, width)
    }

    private fun drawPixelAlignedVerticalLine(
        canvas: Canvas,
        x: Float,
        top: Float,
        bottom: Float,
        paint: Paint
    ): Float {
        val alignedX = pixelAlignedLineCenter(x)
        val width = paint.strokeWidth
        val color = paint.color
        val alpha = Color.alpha(color)
        if (bottom <= top || width <= 0f || alpha <= 0) {
            return alignedX
        }
        val left = alignedX - width / 2f
        val right = alignedX + width / 2f
        val firstPixel = floor(left).toInt()
        val lastPixel = ceil(right).toInt() - 1
        if (lastPixel < firstPixel) {
            return alignedX
        }
        val previousStyle = paint.style
        val previousAntiAlias = paint.isAntiAlias
        paint.style = Paint.Style.FILL
        paint.isAntiAlias = false
        for (pixel in firstPixel..lastPixel) {
            val pixelLeft = pixel.toFloat()
            val coverage = (min(right, pixelLeft + 1f) - max(left, pixelLeft)).coerceIn(0f, 1f)
            if (coverage > 0f) {
                val coveredAlpha = (alpha * coverage).roundToInt().coerceIn(0, 255)
                paint.color = (color and 0x00ffffff) or (coveredAlpha shl 24)
                canvas.drawRect(pixelLeft, top, pixelLeft + 1f, bottom, paint)
            }
        }
        paint.color = color
        paint.style = previousStyle
        paint.isAntiAlias = previousAntiAlias
        return alignedX
    }

    private fun pixelAlignedLineCenter(x: Float): Float {
        return round(x - 0.5f) + 0.5f
    }

    private fun visiblePitchRange(): Double {
        return layout().visiblePitchRange()
    }

    private fun pitchMoveLimit(): Double {
        return layout().pitchMoveLimit()
    }

    private fun visiblePitchMin(): Double {
        return layout().visiblePitchMin()
    }

    private fun visiblePitchMax(): Double {
        return layout().visiblePitchMax()
    }

    private fun hsvColor(hue: Float, saturation: Float, value: Float, alpha: Float): Int {
        hsvScratch[0] = hue
        hsvScratch[1] = saturation
        hsvScratch[2] = value
        return Color.HSVToColor((alpha * 255).roundToInt().coerceIn(0, 255), hsvScratch)
    }

    private fun trackHue(track: Int): Float {
        return TRACK_HUES[trackHueIndex(track)]
    }

    private fun trackHueIndex(track: Int): Int {
        return abs(track) % TRACK_HUES.size
    }

    private fun nowSeconds(): Double {
        return System.nanoTime() / 1_000_000_000.0
    }

    private fun impactKeyForPitch(pitch: Double): Int {
        return (pitch * 10000).roundToInt()
    }

    private data class NoteTone(
        val hue: Float,
        val fillSaturation: Float,
        val value: Float,
        val alpha: Float,
        val strokeSaturation: Float,
        val strokeValue: Float,
        val strokeAlpha: Float,
        val highlightValue: Float,
        val highlightAlpha: Float
    )

    private data class ManualNoteVisual(
        val id: Int,
        val pointerId: Int,
        var pitch: Double,
        var visualPitch: Double,
        var midiPitch: Int,
        var velocity: Int,
        var track: Int,
        val startedAt: Double,
        var releasedAt: Double? = null
    )

    private data class ManualNoteFrame(
        val note: ManualNoteVisual,
        val fade: Float,
        val clippedTop: Float,
        val clippedBottom: Float
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
        private val TRACK_HUES = floatArrayOf(190f, 28f, 132f, 48f, 264f, 158f, 330f, 88f)
        private const val NOTE_TONE_VELOCITY_BUCKETS = 128
        private const val KEYBOARD_GLASS_ACTIVE_REFRESH_NANOS = 33_333_333L
        private const val KEYBOARD_GLASS_WARMUP_FRAMES = 12
    }
}
