package icu.ringona.xensynth.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.os.Handler
import android.os.Looper
import android.util.Log
import icu.ringona.xensynth.midi.MidiWaterfallParser
import icu.ringona.xensynth.midi.ParsedScore
import icu.ringona.xensynth.midi.WaterfallNote
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal data class OpenGlWaterfallScene(
    val score: ParsedScore?,
    val displayState: WaterfallDisplayState,
    val playheadSeconds: Double,
    val playbackActive: Boolean,
    val octaveDivisions: Int,
    val scaleGuide: ScaleGuide,
    val manualNotes: List<OpenGlManualNote>
)

internal data class OpenGlManualNote(
    val visualPitch: Double,
    val velocity: Int,
    val track: Int,
    val startedAtSeconds: Double,
    val releasedAtSeconds: Double?
)

/**
 * OpenGL ES 3.0 renderer for the waterfall scene.
 *
 * Android/UI callers publish immutable scene snapshots through [updateScene]. All OpenGL state,
 * GPU objects, and [WaterfallVisibleNoteIndex] instances are owned by the GLSurfaceView render
 * thread. The hosting view is responsible for selecting its render mode and calling
 * GLSurfaceView.requestRender().
 */
internal class OpenGlWaterfallRenderer(
    context: Context,
    private val onFailure: (Throwable) -> Unit
) : GLSurfaceView.Renderer {
    private val appContext = context.applicationContext
    private val density = context.resources.displayMetrics.density
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sceneReference = AtomicReference<OpenGlWaterfallScene?>(null)
    private val released = AtomicBoolean(false)
    private val failureDelivered = AtomicBoolean(false)

    private val geometry = GeometryBatch()
    private var uploadBuffer: FloatBuffer? = null

    private var viewportWidth = 1
    private var viewportHeight = 1
    private var glThread: Thread? = null
    private var glReady = false

    private var backgroundProgram = 0
    private var backgroundVertexArray = 0
    private var backgroundTexture = 0
    private var backgroundTextureWidth = 0
    private var backgroundTextureHeight = 0
    private var backgroundTextureLocation = -1
    private var backgroundHasTextureLocation = -1
    private var backgroundUvScaleLocation = -1
    private var backgroundUvOffsetLocation = -1

    private var colorProgram = 0
    private var colorVertexArray = 0
    private var colorVertexBuffer = 0
    private var colorViewportLocation = -1

    // These are only read or written from GLSurfaceView.Renderer callbacks.
    private var indexedScore: ParsedScore? = null
    private var visibleNoteIndex: WaterfallVisibleNoteIndex? = null

    /** Publishes a new render snapshot. Safe to call from any thread. */
    fun updateScene(scene: OpenGlWaterfallScene) {
        if (released.get()) {
            return
        }
        // Manual-note collections are frequently assembled from mutable maps in the view layer.
        // Copy the list so the GL thread never observes structural mutation during a frame.
        sceneReference.set(scene.copy(manualNotes = scene.manualNotes.toList()))
    }

    /**
     * Marks this renderer released. If called on the GL thread, resources are deleted immediately;
     * otherwise the next renderer callback performs deletion. EGL context destruction remains the
     * final safety net when no later callback is delivered.
     */
    fun release() {
        released.set(true)
        sceneReference.set(null)
        if (Thread.currentThread() === glThread) {
            releaseGlResources()
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        glThread = Thread.currentThread()
        glReady = false
        indexedScore = null
        visibleNoteIndex = null
        geometry.clear()
        uploadBuffer = null

        GLES30.glClearColor(FALLBACK_CLEAR_RED, FALLBACK_CLEAR_GREEN, FALLBACK_CLEAR_BLUE, 1f)
        if (released.get()) {
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }

        failureDelivered.set(false)
        try {
            // Renderer callbacks only reach this method after GLSurfaceView has created a fresh
            // EGL context. Handles from a lost context are meaningless here; attempting to delete
            // stale program names can itself raise GL_INVALID_VALUE on some drivers.
            forgetGlResources()
            recordAndValidateGlInfo()
            createGlResources()
            glReady = true
            checkGlError("surface creation")
        } catch (error: Throwable) {
            glReady = false
            runCatching { releaseGlResources() }
            reportFailure(error)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        glThread = Thread.currentThread()
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        glThread = Thread.currentThread()
        if (released.get()) {
            if (glReady) {
                releaseGlResources()
            }
            GLES30.glClearColor(FALLBACK_CLEAR_RED, FALLBACK_CLEAR_GREEN, FALLBACK_CLEAR_BLUE, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }

        GLES30.glClearColor(FALLBACK_CLEAR_RED, FALLBACK_CLEAR_GREEN, FALLBACK_CLEAR_BLUE, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (!glReady) {
            return
        }

        try {
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glDisable(GLES30.GL_CULL_FACE)
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            GLES30.glDisable(GLES30.GL_BLEND)
            drawBackground()

            val scene = sceneReference.get()
            if (scene != null) {
                buildSceneGeometry(scene)
                drawSceneGeometry()
            }
            checkGlError("frame rendering")
        } catch (error: Throwable) {
            glReady = false
            reportFailure(error)
        }
    }

    private fun createGlResources() {
        backgroundProgram = linkProgram(BACKGROUND_VERTEX_SHADER, BACKGROUND_FRAGMENT_SHADER)
        backgroundTextureLocation = requireUniform(backgroundProgram, "uTexture")
        backgroundHasTextureLocation = requireUniform(backgroundProgram, "uHasTexture")
        backgroundUvScaleLocation = requireUniform(backgroundProgram, "uUvScale")
        backgroundUvOffsetLocation = requireUniform(backgroundProgram, "uUvOffset")

        val arrays = IntArray(1)
        GLES30.glGenVertexArrays(1, arrays, 0)
        backgroundVertexArray = arrays[0]
        requireGlHandle(backgroundVertexArray, "background vertex array")

        colorProgram = linkProgram(COLOR_VERTEX_SHADER, COLOR_FRAGMENT_SHADER)
        colorViewportLocation = requireUniform(colorProgram, "uViewport")

        GLES30.glGenVertexArrays(1, arrays, 0)
        colorVertexArray = arrays[0]
        requireGlHandle(colorVertexArray, "color vertex array")

        val buffers = IntArray(1)
        GLES30.glGenBuffers(1, buffers, 0)
        colorVertexBuffer = buffers[0]
        requireGlHandle(colorVertexBuffer, "color vertex buffer")

        GLES30.glBindVertexArray(colorVertexArray)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, colorVertexBuffer)
        GLES30.glEnableVertexAttribArray(POSITION_ATTRIBUTE)
        GLES30.glVertexAttribPointer(
            POSITION_ATTRIBUTE,
            POSITION_COMPONENTS,
            GLES30.GL_FLOAT,
            false,
            VERTEX_STRIDE_BYTES,
            0
        )
        GLES30.glEnableVertexAttribArray(COLOR_ATTRIBUTE)
        GLES30.glVertexAttribPointer(
            COLOR_ATTRIBUTE,
            COLOR_COMPONENTS,
            GLES30.GL_FLOAT,
            false,
            VERTEX_STRIDE_BYTES,
            POSITION_COMPONENTS * Float.SIZE_BYTES
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindVertexArray(0)

        backgroundTexture = loadBackgroundTextureOrFallback()
    }

    private fun drawBackground() {
        GLES30.glUseProgram(backgroundProgram)
        GLES30.glBindVertexArray(backgroundVertexArray)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, backgroundTexture)
        GLES30.glUniform1i(backgroundTextureLocation, 0)
        GLES30.glUniform1i(backgroundHasTextureLocation, if (backgroundTexture != 0) 1 else 0)

        val crop = backgroundUvCrop()
        GLES30.glUniform2f(backgroundUvScaleLocation, crop.scaleX, crop.scaleY)
        GLES30.glUniform2f(backgroundUvOffsetLocation, crop.offsetX, crop.offsetY)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, BACKGROUND_VERTEX_COUNT)

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        GLES30.glBindVertexArray(0)
        GLES30.glUseProgram(0)
    }

    private fun buildSceneGeometry(scene: OpenGlWaterfallScene) {
        geometry.clear()
        val width = viewportWidth.toFloat()
        val height = viewportHeight.toFloat()
        if (width <= 0f || height <= 0f) {
            return
        }

        val displayState = sanitizeDisplayState(scene.displayState)
        val playheadSeconds = scene.playheadSeconds.takeIf { it.isFinite() } ?: 0.0
        val layout = WaterfallLayout(
            playheadSeconds = playheadSeconds,
            pixelsPerSecond = displayState.pixelsPerSecond,
            pitchZoomScale = displayState.pitchZoomScale,
            pitchPanSemitones = displayState.pitchPanSemitones,
            waterfallOffsetCents = displayState.waterfallOffsetCents,
            density = density
        )
        val score = scene.score
        val timelinePlayheadSeconds = if (scene.playbackActive) {
            playheadSeconds
        } else {
            score?.let { layout.initialNoteDisplayPlayhead(it.notes) } ?: playheadSeconds
        }

        appendPitchGrid(scene, layout, width, height)
        if (score != null) {
            ensureVisibleNoteIndex(score)
            appendMeasureLines(score, layout, width, height, timelinePlayheadSeconds)
            appendScoreNotes(score, layout, width, height, timelinePlayheadSeconds)
        } else {
            ensureVisibleNoteIndex(null)
        }
        appendManualNotes(scene.manualNotes, layout, width, height)
        appendPlayhead(layout, width, height, playheadSeconds)
    }

    private fun drawSceneGeometry() {
        if (geometry.vertexCount <= 0) {
            return
        }
        val floats = geometry.floatCount
        val buffer = ensureUploadBuffer(floats)
        buffer.clear()
        buffer.put(geometry.values, 0, floats)
        buffer.flip()

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
        GLES30.glBlendFuncSeparate(
            GLES30.GL_SRC_ALPHA,
            GLES30.GL_ONE_MINUS_SRC_ALPHA,
            GLES30.GL_ONE,
            GLES30.GL_ONE_MINUS_SRC_ALPHA
        )
        GLES30.glUseProgram(colorProgram)
        GLES30.glUniform2f(colorViewportLocation, viewportWidth.toFloat(), viewportHeight.toFloat())
        GLES30.glBindVertexArray(colorVertexArray)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, colorVertexBuffer)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            floats * Float.SIZE_BYTES,
            buffer,
            GLES30.GL_STREAM_DRAW
        )
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, geometry.vertexCount)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindVertexArray(0)
        GLES30.glUseProgram(0)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun appendPitchGrid(
        scene: OpenGlWaterfallScene,
        layout: WaterfallLayout,
        width: Float,
        height: Float
    ) {
        val bottom = layout.keyboardTop(height).coerceIn(0f, height)
        if (bottom <= 0f) {
            return
        }
        val minPitch = layout.visiblePitchMin()
        val maxPitch = layout.visiblePitchMax()
        if (!minPitch.isFinite() || !maxPitch.isFinite() || maxPitch <= minPitch) {
            return
        }
        val octaveDivisions = scene.octaveDivisions.coerceIn(0, MAX_OCTAVE_DIVISIONS)
        val guide = scene.scaleGuide
        if (!guide.hasScale(octaveDivisions)) {
            appendLegacyPitchGrid(layout, width, bottom, minPitch, maxPitch, octaveDivisions)
            return
        }

        val minPitchSpacing = pitchSpacingForPixels(minPitch, maxPitch, width, GRID_MIN_LINE_SPACING_PX)
        var emittedLines = 0
        guide.forEachLineInVisibleRange(
            octaveDivisions,
            minPitch,
            maxPitch,
            minPitchSpacing
        ) { pitch, ratio, strokeRatio, hasStrokeRatio, isC ->
            if (emittedLines >= MAX_GRID_LINES || !pitch.isFinite() || !ratio.isFinite()) {
                return@forEachLineInVisibleRange
            }
            val x = layout.pitchToX(pitch, width)
            if (!x.isFinite() || x < -2f || x > width + 2f) {
                return@forEachLineInVisibleRange
            }
            val safeRatio = ratio.coerceIn(0f, 1f)
            val alpha = WaterfallMetrics.OCTAVE_LINE_ALPHA / 255f * safeRatio
            val strokeWidth = when {
                hasStrokeRatio -> 1.5f * strokeRatio.coerceAtLeast(0f)
                isC -> 1.5f
                else -> 1f
            }
            appendVerticalLine(x, 0f, bottom, strokeWidth.coerceAtLeast(0.5f), Color4.WHITE.withAlpha(alpha))
            emittedLines++
        }
    }

    private fun appendLegacyPitchGrid(
        layout: WaterfallLayout,
        width: Float,
        bottom: Float,
        minPitch: Double,
        maxPitch: Double,
        octaveDivisions: Int
    ) {
        var emittedLines = 0
        var octavePitch = floor(minPitch / OCTAVE_SEMITONES).toInt() * OCTAVE_SEMITONES.toInt()
        while (octavePitch <= maxPitch + OCTAVE_SEMITONES && emittedLines < MAX_GRID_LINES) {
            val x = layout.pitchToX(octavePitch.toDouble(), width)
            if (x.isFinite() && x >= -2f && x <= width + 2f) {
                appendVerticalLine(
                    x,
                    0f,
                    bottom,
                    1.5f,
                    Color4.WHITE.withAlpha(WaterfallMetrics.OCTAVE_LINE_ALPHA / 255f)
                )
                emittedLines++
            }
            octavePitch += OCTAVE_SEMITONES.toInt()
        }

        if (octaveDivisions <= 0 || emittedLines >= MAX_GRID_LINES) {
            return
        }
        val step = OCTAVE_SEMITONES / octaveDivisions
        val minPitchSpacing = pitchSpacingForPixels(minPitch, maxPitch, width, GRID_MIN_LINE_SPACING_PX)
        var stepIndex = floor(minPitch / step).toInt()
        var pitch = stepIndex * step
        while (pitch <= maxPitch + step && emittedLines < MAX_GRID_LINES) {
            val octaveOffset = positiveModulo(pitch, OCTAVE_SEMITONES)
            if (octaveOffset > PITCH_EPSILON && OCTAVE_SEMITONES - octaveOffset > PITCH_EPSILON) {
                val x = layout.pitchToX(pitch, width)
                val visibilityRatio = DenseLineVisibility.ratioForStep(stepIndex, step, minPitchSpacing)
                if (visibilityRatio > 0f && x.isFinite() && x >= 0f && x <= width) {
                    val alpha = WaterfallMetrics.GRID_LINE_ALPHA / 255f * visibilityRatio.coerceIn(0f, 1f)
                    appendVerticalLine(x, 0f, bottom, 1f, Color4.WHITE.withAlpha(alpha))
                    emittedLines++
                }
            }
            stepIndex++
            pitch += step
        }
    }

    private fun appendMeasureLines(
        score: ParsedScore,
        layout: WaterfallLayout,
        width: Float,
        height: Float,
        timelinePlayheadSeconds: Double
    ) {
        if (score.meters.isEmpty() || score.tempoMap.isEmpty() || score.ticksPerQuarter <= 0) {
            return
        }
        val bottom = layout.keyboardTop(height).coerceIn(0f, height)
        val visibleTimeRange = layout.visibleTimeRange(height, timelinePlayheadSeconds)
        val visibleStart = max(0.0, visibleTimeRange.start)
        val visibleEnd = visibleTimeRange.end
        if (!visibleStart.isFinite() || !visibleEnd.isFinite() || visibleEnd < visibleStart) {
            return
        }
        val startTick = MidiWaterfallParser.secondsToTick(
            visibleStart,
            score.tempoMap,
            score.ticksPerQuarter
        )
        val endTick = MidiWaterfallParser.secondsToTick(
            visibleEnd,
            score.tempoMap,
            score.ticksPerQuarter
        )
        if (!startTick.isFinite() || !endTick.isFinite()) {
            return
        }

        var emittedLines = 0
        score.meters.forEachIndexed { index, meter ->
            if (emittedLines >= MAX_MEASURE_LINES) {
                return@forEachIndexed
            }
            val stepTicks = MidiWaterfallParser.measureTicks(meter, score.ticksPerQuarter)
            if (!stepTicks.isFinite() || stepTicks <= 0.0) {
                return@forEachIndexed
            }
            val segmentStart = max(0.0, meter.tick.toDouble())
            val segmentEnd = if (index + 1 < score.meters.size) {
                score.meters[index + 1].tick.toDouble()
            } else {
                endTick + stepTicks
            }
            if (!segmentEnd.isFinite() || segmentEnd <= segmentStart) {
                return@forEachIndexed
            }
            val firstIndex = max(0, ceil((startTick - segmentStart) / stepTicks).toInt())
            val lastIndex = floor((min(endTick, segmentEnd - TICK_EPSILON) - segmentStart) / stepTicks).toInt()
            if (lastIndex < firstIndex) {
                return@forEachIndexed
            }
            var localIndex = firstIndex
            while (localIndex <= lastIndex && emittedLines < MAX_MEASURE_LINES) {
                val tick = segmentStart + localIndex * stepTicks
                val second = MidiWaterfallParser.tickToSeconds(
                    tick.roundToLong(),
                    score.tempoMap,
                    score.ticksPerQuarter
                )
                val y = layout.timeToY(second, height, timelinePlayheadSeconds)
                if (y.isFinite() && y >= 0f && y <= bottom) {
                    appendHorizontalLine(
                        y,
                        0f,
                        width,
                        1f,
                        Color4.WHITE.withAlpha(MEASURE_LINE_ALPHA)
                    )
                    emittedLines++
                }
                localIndex++
            }
        }
    }

    private fun appendScoreNotes(
        score: ParsedScore,
        layout: WaterfallLayout,
        width: Float,
        height: Float,
        timelinePlayheadSeconds: Double
    ) {
        val notes = score.notes
        if (notes.isEmpty()) {
            return
        }
        val keyboardTop = layout.keyboardTop(height)
        val visibleTimeRange = layout.visibleTimeRange(height, timelinePlayheadSeconds)
        val visibleStart = visibleTimeRange.start
        val visibleEnd = visibleTimeRange.end
        val minPitch = layout.visiblePitchMin() - 1.0
        val maxPitch = layout.visiblePitchMax() + 1.0
        val noteWidth = layout.noteWidth(width)
        val halfNoteWidth = noteWidth / 2f
        var emittedNotes = 0

        fun appendNote(note: WaterfallNote) {
            if (emittedNotes >= MAX_VISIBLE_NOTES ||
                !note.start.isFinite() ||
                !note.end.isFinite() ||
                !note.pitch.isFinite()
            ) {
                return
            }
            val pitch = layout.renderedPitch(note)
            if (!pitch.isFinite() || pitch < minPitch || pitch > maxPitch) {
                return
            }
            val x = layout.pitchToX(pitch, width)
            val yStart = layout.timeToY(note.start, height, timelinePlayheadSeconds)
            val yEnd = layout.timeToY(note.end, height, timelinePlayheadSeconds)
            if (!x.isFinite() || !yStart.isFinite() || !yEnd.isFinite()) {
                return
            }
            val left = x - halfNoteWidth
            val right = x + halfNoteWidth
            var top = max(-NOTE_CLIP_MARGIN_PX, min(yStart, yEnd))
            var bottom = min(keyboardTop + NOTE_CLIP_MARGIN_PX, max(yStart, yEnd))
            if (bottom < 0f || top > keyboardTop) {
                return
            }
            if (bottom - top < WaterfallMetrics.NOTE_MIN_HEIGHT_PX) {
                bottom = min(keyboardTop + NOTE_CLIP_MARGIN_PX, top + WaterfallMetrics.NOTE_MIN_HEIGHT_PX)
            }
            if (right <= left || bottom <= top) {
                return
            }

            val tone = velocityNoteTone(note.track, note.velocity)
            appendOutlinedNoteRect(left, top, right, bottom, tone)
            emittedNotes++
        }

        val indices = visibleNoteIndex?.visibleNoteIndices(visibleStart, visibleEnd)
        if (indices != null) {
            for (index in indices) {
                if (emittedNotes >= MAX_VISIBLE_NOTES) {
                    break
                }
                if (index in notes.indices) {
                    appendNote(notes[index])
                }
            }
            return
        }

        for (note in notes) {
            if (emittedNotes >= MAX_VISIBLE_NOTES || note.start > visibleEnd) {
                break
            }
            if (note.end >= visibleStart) {
                appendNote(note)
            }
        }
    }

    private fun appendOutlinedNoteRect(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        tone: NoteTone
    ) {
        val stroke = hsvColor(tone.hue, tone.strokeSaturation, tone.strokeValue, tone.strokeAlpha)
        val fill = hsvColor(tone.hue, tone.fillSaturation, tone.value, tone.alpha)
        geometry.appendSolidRect(left, top, right, bottom, stroke)

        val inset = min(NOTE_STROKE_INSET_PX, min((right - left) * 0.22f, (bottom - top) * 0.22f))
        if (inset > 0f && right - left > inset * 2f && bottom - top > inset * 2f) {
            geometry.appendSolidRect(left + inset, top + inset, right - inset, bottom - inset, fill)
        } else {
            geometry.appendSolidRect(left, top, right, bottom, fill)
        }

        if (bottom - top > 6f && right - left > 2.5f) {
            val highlightBottom = min(bottom, top + 2f)
            geometry.appendSolidRect(
                left + 0.6f,
                top + 0.6f,
                right - 0.6f,
                highlightBottom,
                hsvColor(tone.hue, 0.70f, tone.highlightValue, tone.highlightAlpha)
            )
        }
    }

    private fun appendManualNotes(
        notes: List<OpenGlManualNote>,
        layout: WaterfallLayout,
        width: Float,
        height: Float
    ) {
        if (notes.isEmpty()) {
            return
        }
        val now = System.nanoTime() / NANOS_PER_SECOND
        val keyboardTop = layout.keyboardTop(height)
        val fadeDistance = max(WaterfallMetrics.MANUAL_NOTE_FADE_DISTANCE, height * 0.92f)
        val noteWidth = max(
            WaterfallMetrics.NOTE_MIN_WIDTH_PX + 1f,
            min(
                WaterfallMetrics.NOTE_MAX_WIDTH_PX + 2f,
                width / WaterfallMetrics.NOTE_RANGE.toFloat() * 0.22f
            )
        )

        var emittedNotes = 0
        for (note in notes) {
            if (emittedNotes >= MAX_MANUAL_NOTES ||
                !note.visualPitch.isFinite() ||
                !note.startedAtSeconds.isFinite()
            ) {
                continue
            }
            val releasedAt = note.releasedAtSeconds?.takeIf { it.isFinite() }
            val heldUntil = releasedAt ?: now
            val heldSeconds = max(WaterfallMetrics.MANUAL_NOTE_MIN_SECONDS, heldUntil - note.startedAtSeconds)
            val releaseAge = releasedAt?.let { max(0.0, now - it) } ?: 0.0
            val travel = (releaseAge * layout.pixelsPerSecond).toFloat()
            val fade = if (releasedAt == null) {
                1f
            } else {
                (1f - travel / fadeDistance).coerceIn(0f, 1f)
            }
            val bottom = keyboardTop - travel
            val top = bottom - max(
                WaterfallMetrics.MANUAL_NOTE_MIN_HEIGHT,
                (heldSeconds * layout.pixelsPerSecond).toFloat()
            )
            if (fade <= 0f || bottom < -WaterfallMetrics.MANUAL_NOTE_OFFSCREEN_MARGIN || top > height) {
                continue
            }
            val clippedTop = max(-NOTE_CLIP_MARGIN_PX, top)
            val clippedBottom = min(height + NOTE_CLIP_MARGIN_PX, bottom)
            if (clippedBottom <= clippedTop) {
                continue
            }
            val x = layout.pitchToX(note.visualPitch, width)
            if (!x.isFinite()) {
                continue
            }
            val left = x - noteWidth / 2f
            val right = x + noteWidth / 2f
            val tone = velocityNoteTone(note.track, note.velocity)
            val alpha = tone.alpha * (0.38f + fade * 0.62f)
            val stroke = hsvColor(
                tone.hue,
                tone.strokeSaturation,
                tone.strokeValue,
                tone.strokeAlpha * (0.48f + fade * 0.52f)
            )
            geometry.appendSolidRect(left, clippedTop, right, clippedBottom, stroke)

            val inset = min(MANUAL_NOTE_STROKE_INSET_PX, (right - left) * 0.18f)
            val innerLeft = left + inset
            val innerRight = right - inset
            val innerTop = clippedTop + inset
            val innerBottom = clippedBottom - inset
            if (innerRight > innerLeft && innerBottom > innerTop) {
                val middle = innerTop + (innerBottom - innerTop) * MANUAL_GRADIENT_MIDDLE_STOP
                val topColor = hsvColor(tone.hue, 0.96f, tone.highlightValue, alpha * 0.95f)
                val middleColor = hsvColor(tone.hue, tone.fillSaturation, tone.value, alpha)
                val bottomColor = hsvColor(
                    tone.hue,
                    0.82f,
                    max(0.36f, tone.value - 0.26f),
                    alpha * 0.34f
                )
                geometry.appendVerticalGradientRect(
                    innerLeft,
                    innerTop,
                    innerRight,
                    middle,
                    topColor,
                    middleColor
                )
                geometry.appendVerticalGradientRect(
                    innerLeft,
                    middle,
                    innerRight,
                    innerBottom,
                    middleColor,
                    bottomColor
                )
            }
            emittedNotes++
        }
    }

    private fun appendPlayhead(
        layout: WaterfallLayout,
        width: Float,
        height: Float,
        playheadSeconds: Double
    ) {
        val y = layout.timeToY(playheadSeconds, height)
        if (!y.isFinite()) {
            return
        }
        appendHorizontalLine(y, 0f, width, PLAYHEAD_WIDTH_PX, PLAYHEAD_COLOR)
    }

    private fun appendVerticalLine(
        x: Float,
        top: Float,
        bottom: Float,
        strokeWidth: Float,
        color: Color4
    ) {
        val alignedX = round(x - 0.5f) + 0.5f
        val halfWidth = strokeWidth.coerceAtLeast(0.5f) / 2f
        geometry.appendSolidRect(alignedX - halfWidth, top, alignedX + halfWidth, bottom, color)
    }

    private fun appendHorizontalLine(
        y: Float,
        left: Float,
        right: Float,
        strokeWidth: Float,
        color: Color4
    ) {
        val alignedY = round(y - 0.5f) + 0.5f
        val halfWidth = strokeWidth.coerceAtLeast(0.5f) / 2f
        geometry.appendSolidRect(left, alignedY - halfWidth, right, alignedY + halfWidth, color)
    }

    private fun ensureVisibleNoteIndex(score: ParsedScore?) {
        if (score === indexedScore) {
            return
        }
        indexedScore = score
        visibleNoteIndex = score?.let { WaterfallVisibleNoteIndex(it.notes) }
    }

    private fun sanitizeDisplayState(state: WaterfallDisplayState): WaterfallDisplayState {
        val pixelsPerSecond = state.pixelsPerSecond
            .takeIf { it.isFinite() }
            ?.coerceIn(WaterfallMetrics.TIME_ZOOM_MIN, WaterfallMetrics.TIME_ZOOM_MAX)
            ?: WaterfallMetrics.DEFAULT_PIXELS_PER_SECOND
        val pitchZoomScale = state.pitchZoomScale
            .takeIf { it.isFinite() }
            ?.coerceIn(WaterfallMetrics.PITCH_ZOOM_MIN, WaterfallMetrics.PITCH_ZOOM_MAX)
            ?: WaterfallMetrics.PITCH_ZOOM_DEFAULT
        val pitchPanSemitones = state.pitchPanSemitones
            .takeIf { it.isFinite() }
            ?.let { WaterfallMetrics.coercePitchPan(pitchZoomScale, it) }
            ?: 0.0
        val offsetCents = state.waterfallOffsetCents
            .takeIf { it.isFinite() }
            ?.coerceIn(-WaterfallMetrics.OFFSET_CENT_RANGE, WaterfallMetrics.OFFSET_CENT_RANGE)
            ?: 0.0
        return WaterfallDisplayState(
            pixelsPerSecond = pixelsPerSecond,
            pitchZoomScale = pitchZoomScale,
            pitchPanSemitones = pitchPanSemitones,
            waterfallOffsetCents = offsetCents
        )
    }

    private fun velocityNoteTone(track: Int, velocity: Int): NoteTone {
        val ratio = velocity.coerceIn(0, 127) / 127f
        val emphasis = ratio.pow(0.72f)
        val value = 0.58f + emphasis * 0.40f
        val lowContrastOutline = value < 0.74f
        return NoteTone(
            hue = TRACK_HUES[Math.floorMod(track, TRACK_HUES.size)],
            fillSaturation = 0.74f + ratio * 0.12f,
            value = value,
            alpha = 0.76f + ratio * 0.20f,
            strokeSaturation = 0.82f + ratio * 0.10f,
            strokeValue = if (lowContrastOutline) min(0.98f, value + 0.24f) else max(0.30f, value - 0.52f),
            strokeAlpha = if (lowContrastOutline) 0.62f else 0.74f,
            highlightValue = min(1f, value + 0.18f),
            highlightAlpha = if (lowContrastOutline) 0.22f else 0.14f
        )
    }

    private fun hsvColor(hue: Float, saturation: Float, value: Float, alpha: Float): Color4 {
        val normalizedHue = positiveModulo(hue.toDouble(), 360.0).toFloat()
        val safeSaturation = saturation.coerceIn(0f, 1f)
        val safeValue = value.coerceIn(0f, 1f)
        val chroma = safeValue * safeSaturation
        val hueSegment = normalizedHue / 60f
        val x = chroma * (1f - abs(hueSegment % 2f - 1f))
        val (red, green, blue) = when (floor(hueSegment).toInt()) {
            0 -> Triple(chroma, x, 0f)
            1 -> Triple(x, chroma, 0f)
            2 -> Triple(0f, chroma, x)
            3 -> Triple(0f, x, chroma)
            4 -> Triple(x, 0f, chroma)
            else -> Triple(chroma, 0f, x)
        }
        val match = safeValue - chroma
        return Color4(red + match, green + match, blue + match, alpha.coerceIn(0f, 1f))
    }

    private fun pitchSpacingForPixels(
        minPitch: Double,
        maxPitch: Double,
        width: Float,
        minPixels: Float
    ): Double {
        if (width <= 0f || minPixels <= 0f || maxPitch <= minPitch) {
            return 0.0
        }
        return (maxPitch - minPitch) * minPixels / width.toDouble().coerceAtLeast(1.0)
    }

    private fun backgroundUvCrop(): UvCrop {
        if (backgroundTexture == 0 || backgroundTextureWidth <= 0 || backgroundTextureHeight <= 0) {
            return UvCrop.Identity
        }
        val textureAspect = backgroundTextureWidth.toFloat() / backgroundTextureHeight.toFloat()
        val viewportAspect = viewportWidth.toFloat() / viewportHeight.toFloat().coerceAtLeast(1f)
        return if (textureAspect > viewportAspect) {
            val scaleX = (viewportAspect / textureAspect).coerceIn(0f, 1f)
            UvCrop(scaleX, 1f, (1f - scaleX) / 2f, 0f)
        } else {
            val scaleY = (textureAspect / viewportAspect).coerceIn(0f, 1f)
            UvCrop(1f, scaleY, 0f, (1f - scaleY) / 2f)
        }
    }

    private fun loadBackgroundTextureOrFallback(): Int {
        backgroundTextureWidth = 0
        backgroundTextureHeight = 0
        return try {
            loadBackgroundTexture()
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to upload $BACKGROUND_ASSET_PATH; using shader gradient", error)
            backgroundTextureWidth = 0
            backgroundTextureHeight = 0
            0
        }
    }

    private fun loadBackgroundTexture(): Int {
        val options = BitmapFactory.Options().apply {
            inScaled = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val source = appContext.assets.open(BACKGROUND_ASSET_PATH).use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: throw IllegalStateException("Unable to decode $BACKGROUND_ASSET_PATH")

        var uploadBitmap: Bitmap = source
        var texture = 0
        try {
            val maxTextureSize = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0)
            val maxDimension = maxTextureSize[0].coerceAtLeast(1)
            val scale = min(
                1f,
                min(
                    maxDimension.toFloat() / source.width.coerceAtLeast(1),
                    maxDimension.toFloat() / source.height.coerceAtLeast(1)
                )
            )
            if (scale < 1f) {
                uploadBitmap = Bitmap.createScaledBitmap(
                    source,
                    max(1, (source.width * scale).roundToInt()),
                    max(1, (source.height * scale).roundToInt()),
                    true
                )
            }

            val textures = IntArray(1)
            GLES30.glGenTextures(1, textures, 0)
            texture = textures[0]
            requireGlHandle(texture, "background texture")
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, uploadBitmap, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
            checkGlError("background texture upload")

            backgroundTextureWidth = uploadBitmap.width
            backgroundTextureHeight = uploadBitmap.height
            return texture
        } catch (error: Throwable) {
            if (texture != 0) {
                GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
            }
            throw error
        } finally {
            if (uploadBitmap !== source) {
                uploadBitmap.recycle()
            }
            source.recycle()
        }
    }

    private fun ensureUploadBuffer(floatCount: Int): FloatBuffer {
        val current = uploadBuffer
        if (current != null && current.capacity() >= floatCount) {
            return current
        }
        var capacity = current?.capacity()?.coerceAtLeast(INITIAL_UPLOAD_FLOAT_CAPACITY)
            ?: INITIAL_UPLOAD_FLOAT_CAPACITY
        while (capacity < floatCount) {
            capacity = min(MAX_GEOMETRY_FLOATS, capacity * 2)
            if (capacity < floatCount && capacity == MAX_GEOMETRY_FLOATS) {
                throw IllegalStateException("Waterfall geometry exceeds $MAX_GEOMETRY_FLOATS floats")
            }
        }
        return ByteBuffer
            .allocateDirect(capacity * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .also { uploadBuffer = it }
    }

    private fun recordAndValidateGlInfo() {
        val vendor = GLES30.glGetString(GLES30.GL_VENDOR).orEmpty()
        val renderer = GLES30.glGetString(GLES30.GL_RENDERER).orEmpty()
        val version = GLES30.glGetString(GLES30.GL_VERSION).orEmpty()
        val shadingLanguage = GLES30.glGetString(GLES30.GL_SHADING_LANGUAGE_VERSION).orEmpty()
        val versionMatch = OPEN_GL_ES_VERSION.find(version)
        val majorVersion = versionMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        if (majorVersion < 3) {
            throw IllegalStateException("OpenGL ES 3.0 required, reported version='$version'")
        }
        val maxTextureSize = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0)
        Log.i(
            TAG,
            "GL initialized vendor='$vendor' renderer='$renderer' version='$version' " +
                "glsl='$shadingLanguage' maxTexture=${maxTextureSize[0]}"
        )
    }

    private fun linkProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = try {
            compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        } catch (error: Throwable) {
            GLES30.glDeleteShader(vertexShader)
            throw error
        }
        val program = GLES30.glCreateProgram()
        if (program == 0) {
            GLES30.glDeleteShader(vertexShader)
            GLES30.glDeleteShader(fragmentShader)
            throw IllegalStateException("glCreateProgram returned 0")
        }
        try {
            GLES30.glAttachShader(program, vertexShader)
            GLES30.glAttachShader(program, fragmentShader)
            GLES30.glLinkProgram(program)
            val status = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
            if (status[0] != GLES30.GL_TRUE) {
                val info = GLES30.glGetProgramInfoLog(program).orEmpty()
                throw IllegalStateException("OpenGL program link failed: $info")
            }
            return program
        } catch (error: Throwable) {
            GLES30.glDeleteProgram(program)
            throw error
        } finally {
            GLES30.glDetachShader(program, vertexShader)
            GLES30.glDetachShader(program, fragmentShader)
            GLES30.glDeleteShader(vertexShader)
            GLES30.glDeleteShader(fragmentShader)
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        if (shader == 0) {
            throw IllegalStateException("glCreateShader returned 0 for type=$type")
        }
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES30.GL_TRUE) {
            val info = GLES30.glGetShaderInfoLog(shader).orEmpty()
            GLES30.glDeleteShader(shader)
            throw IllegalStateException("OpenGL shader compile failed type=$type: $info")
        }
        return shader
    }

    private fun requireUniform(program: Int, name: String): Int {
        val location = GLES30.glGetUniformLocation(program, name)
        if (location < 0) {
            throw IllegalStateException("Required OpenGL uniform '$name' was optimized out or not found")
        }
        return location
    }

    private fun requireGlHandle(handle: Int, label: String) {
        if (handle == 0) {
            throw IllegalStateException("Unable to create OpenGL $label")
        }
    }

    private fun checkGlError(operation: String) {
        val errors = ArrayList<String>(2)
        var error = GLES30.glGetError()
        var count = 0
        while (error != GLES30.GL_NO_ERROR && count < MAX_REPORTED_GL_ERRORS) {
            errors += "0x${Integer.toHexString(error)}"
            error = GLES30.glGetError()
            count++
        }
        if (errors.isNotEmpty()) {
            throw IllegalStateException("OpenGL error after $operation: ${errors.joinToString()}")
        }
    }

    private fun releaseGlResources() {
        if (backgroundTexture != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(backgroundTexture), 0)
        }
        if (colorVertexBuffer != 0) {
            GLES30.glDeleteBuffers(1, intArrayOf(colorVertexBuffer), 0)
        }
        if (backgroundVertexArray != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(backgroundVertexArray), 0)
        }
        if (colorVertexArray != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(colorVertexArray), 0)
        }
        if (backgroundProgram != 0) {
            GLES30.glDeleteProgram(backgroundProgram)
        }
        if (colorProgram != 0) {
            GLES30.glDeleteProgram(colorProgram)
        }

        forgetGlResources()
    }

    private fun forgetGlResources() {
        backgroundProgram = 0
        backgroundVertexArray = 0
        backgroundTexture = 0
        backgroundTextureWidth = 0
        backgroundTextureHeight = 0
        backgroundTextureLocation = -1
        backgroundHasTextureLocation = -1
        backgroundUvScaleLocation = -1
        backgroundUvOffsetLocation = -1
        colorProgram = 0
        colorVertexArray = 0
        colorVertexBuffer = 0
        colorViewportLocation = -1
        indexedScore = null
        visibleNoteIndex = null
        glReady = false
    }

    private fun reportFailure(error: Throwable) {
        Log.e(TAG, "OpenGL waterfall renderer failed", error)
        if (!failureDelivered.compareAndSet(false, true)) {
            return
        }
        mainHandler.post {
            try {
                onFailure(error)
            } catch (callbackError: Throwable) {
                Log.e(TAG, "OpenGL failure callback failed", callbackError)
            }
        }
    }

    private fun positiveModulo(value: Double, modulus: Double): Double {
        return ((value % modulus) + modulus) % modulus
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

    private data class UvCrop(
        val scaleX: Float,
        val scaleY: Float,
        val offsetX: Float,
        val offsetY: Float
    ) {
        companion object {
            val Identity = UvCrop(1f, 1f, 0f, 0f)
        }
    }

    private data class Color4(
        val red: Float,
        val green: Float,
        val blue: Float,
        val alpha: Float
    ) {
        fun withAlpha(nextAlpha: Float): Color4 = copy(alpha = nextAlpha.coerceIn(0f, 1f))

        companion object {
            val WHITE = Color4(1f, 1f, 1f, 1f)
        }
    }

    private class GeometryBatch {
        var values = FloatArray(INITIAL_GEOMETRY_FLOAT_CAPACITY)
            private set
        var floatCount = 0
            private set

        val vertexCount: Int
            get() = floatCount / FLOATS_PER_VERTEX

        fun clear() {
            floatCount = 0
        }

        fun appendSolidRect(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            color: Color4
        ) {
            appendVerticalGradientRect(left, top, right, bottom, color, color)
        }

        fun appendVerticalGradientRect(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            topColor: Color4,
            bottomColor: Color4
        ) {
            if (!left.isFinite() ||
                !top.isFinite() ||
                !right.isFinite() ||
                !bottom.isFinite() ||
                right <= left ||
                bottom <= top ||
                topColor.alpha <= 0f && bottomColor.alpha <= 0f
            ) {
                return
            }
            ensureCapacity(TRIANGLE_RECT_FLOATS)
            appendVertex(left, top, topColor)
            appendVertex(left, bottom, bottomColor)
            appendVertex(right, top, topColor)
            appendVertex(right, top, topColor)
            appendVertex(left, bottom, bottomColor)
            appendVertex(right, bottom, bottomColor)
        }

        private fun appendVertex(x: Float, y: Float, color: Color4) {
            values[floatCount++] = x
            values[floatCount++] = y
            values[floatCount++] = color.red.coerceIn(0f, 1f)
            values[floatCount++] = color.green.coerceIn(0f, 1f)
            values[floatCount++] = color.blue.coerceIn(0f, 1f)
            values[floatCount++] = color.alpha.coerceIn(0f, 1f)
        }

        private fun ensureCapacity(additionalFloats: Int) {
            val required = floatCount + additionalFloats
            if (required <= values.size) {
                return
            }
            if (required > MAX_GEOMETRY_FLOATS) {
                throw IllegalStateException("Waterfall geometry exceeds $MAX_GEOMETRY_FLOATS floats")
            }
            var nextSize = values.size
            while (nextSize < required) {
                nextSize = min(MAX_GEOMETRY_FLOATS, nextSize * 2)
            }
            values = values.copyOf(nextSize)
        }
    }

    private companion object {
        const val TAG = "OpenGlWaterfall"
        const val BACKGROUND_ASSET_PATH = "drawable/waterfall.webp"
        const val NANOS_PER_SECOND = 1_000_000_000.0

        const val POSITION_ATTRIBUTE = 0
        const val COLOR_ATTRIBUTE = 1
        const val POSITION_COMPONENTS = 2
        const val COLOR_COMPONENTS = 4
        const val FLOATS_PER_VERTEX = POSITION_COMPONENTS + COLOR_COMPONENTS
        const val VERTEX_STRIDE_BYTES = FLOATS_PER_VERTEX * Float.SIZE_BYTES
        const val TRIANGLE_RECT_FLOATS = 6 * FLOATS_PER_VERTEX
        const val BACKGROUND_VERTEX_COUNT = 6

        const val INITIAL_GEOMETRY_FLOAT_CAPACITY = 16_384
        const val INITIAL_UPLOAD_FLOAT_CAPACITY = 16_384
        // Bound the three simultaneous CPU/direct/GPU geometry copies on dense scores.
        const val MAX_GEOMETRY_FLOATS = 2_000_000
        const val MAX_VISIBLE_NOTES = 12_000
        const val MAX_MANUAL_NOTES = 256
        const val MAX_GRID_LINES = 4_096
        const val MAX_MEASURE_LINES = 4_096
        const val MAX_REPORTED_GL_ERRORS = 8
        const val MAX_OCTAVE_DIVISIONS = 240

        const val OCTAVE_SEMITONES = 12.0
        const val PITCH_EPSILON = 0.0001
        const val TICK_EPSILON = 0.0001
        const val GRID_MIN_LINE_SPACING_PX = 1.35f
        const val MEASURE_LINE_ALPHA = 54f / 255f
        const val NOTE_CLIP_MARGIN_PX = 8f
        const val NOTE_STROKE_INSET_PX = 0.55f
        const val MANUAL_NOTE_STROKE_INSET_PX = 0.6f
        const val MANUAL_GRADIENT_MIDDLE_STOP = 0.22f
        const val PLAYHEAD_WIDTH_PX = 2f

        const val FALLBACK_CLEAR_RED = 0.035f
        const val FALLBACK_CLEAR_GREEN = 0.033f
        const val FALLBACK_CLEAR_BLUE = 0.030f

        val PLAYHEAD_COLOR = Color4(1f, 222f / 255f, 111f / 255f, 1f)
        val TRACK_HUES = floatArrayOf(190f, 28f, 132f, 48f, 264f, 158f, 330f, 88f)
        val OPEN_GL_ES_VERSION = Regex("OpenGL ES\\s+(\\d+)(?:\\.(\\d+))?")

        val BACKGROUND_VERTEX_SHADER = """
            #version 300 es
            precision highp float;

            out vec2 vScreenUv;

            const vec2 POSITIONS[6] = vec2[](
                vec2(-1.0, -1.0),
                vec2( 1.0, -1.0),
                vec2(-1.0,  1.0),
                vec2(-1.0,  1.0),
                vec2( 1.0, -1.0),
                vec2( 1.0,  1.0)
            );

            void main() {
                vec2 position = POSITIONS[gl_VertexID];
                gl_Position = vec4(position, 0.0, 1.0);
                vScreenUv = vec2(position.x * 0.5 + 0.5, 0.5 - position.y * 0.5);
            }
        """.trimIndent()

        val BACKGROUND_FRAGMENT_SHADER = """
            #version 300 es
            precision mediump float;

            in vec2 vScreenUv;
            uniform sampler2D uTexture;
            uniform int uHasTexture;
            uniform vec2 uUvScale;
            uniform vec2 uUvOffset;
            out vec4 fragmentColor;

            vec3 overlay(vec3 base, vec3 tint, float alpha) {
                return mix(base, tint, clamp(alpha, 0.0, 1.0));
            }

            void main() {
                float y = clamp(vScreenUv.y, 0.0, 1.0);
                vec3 fallbackTop = vec3(0.105, 0.096, 0.084);
                vec3 fallbackMiddle = vec3(0.050, 0.049, 0.047);
                vec3 fallbackBottom = vec3(0.090, 0.073, 0.055);
                vec3 color = y < 0.52
                    ? mix(fallbackTop, fallbackMiddle, y / 0.52)
                    : mix(fallbackMiddle, fallbackBottom, (y - 0.52) / 0.48);

                if (uHasTexture == 1) {
                    vec2 textureUv = uUvOffset + vScreenUv * uUvScale;
                    vec3 sampled = texture(uTexture, textureUv).rgb;
                    color = mix(sampled, sampled * vec3(0.70, 0.68, 0.65), 0.34);
                }

                vec3 scrimTop = vec3(23.0, 22.0, 20.0) / 255.0;
                vec3 scrimMiddle = vec3(13.0) / 255.0;
                vec3 scrimBottom = vec3(25.0, 21.0, 17.0) / 255.0;
                vec3 scrim = y < 0.52
                    ? mix(scrimTop, scrimMiddle, y / 0.52)
                    : mix(scrimMiddle, scrimBottom, (y - 0.52) / 0.48);
                float scrimAlpha = y < 0.52
                    ? mix(36.0 / 255.0, 30.0 / 255.0, y / 0.52)
                    : mix(30.0 / 255.0, 46.0 / 255.0, (y - 0.52) / 0.48);
                color = overlay(color, scrim, scrimAlpha);

                float topHighlight = (24.0 / 255.0) * (1.0 - smoothstep(0.0, 0.28, y));
                color = overlay(color, vec3(1.0), topHighlight);
                float bottomGlow = (22.0 / 255.0) * smoothstep(0.54, 1.0, y);
                color = overlay(color, vec3(70.0, 56.0, 38.0) / 255.0, bottomGlow);

                float rulerTop = 0.882;
                float glassY = clamp(y / max(rulerTop, 0.001), 0.0, 1.0);
                float glassAlpha = mix(12.0 / 255.0, 18.0 / 255.0, glassY);
                color = overlay(color, vec3(10.0, 10.0, 11.0) / 255.0, glassAlpha);
                float glassBottom = (26.0 / 255.0) * smoothstep(0.34, 1.0, glassY);
                color = overlay(color, vec3(8.0 / 255.0), glassBottom);
                float gleam = (14.0 / 255.0) * (1.0 - smoothstep(0.0, 0.16, glassY));
                color = overlay(color, vec3(118.0, 114.0, 104.0) / 255.0, gleam);

                fragmentColor = vec4(clamp(color, 0.0, 1.0), 1.0);
            }
        """.trimIndent()

        val COLOR_VERTEX_SHADER = """
            #version 300 es
            precision highp float;

            layout(location = 0) in vec2 aPosition;
            layout(location = 1) in vec4 aColor;
            uniform vec2 uViewport;
            out vec4 vColor;

            void main() {
                vec2 safeViewport = max(uViewport, vec2(1.0));
                vec2 normalized = aPosition / safeViewport;
                vec2 clip = normalized * 2.0 - 1.0;
                gl_Position = vec4(clip.x, -clip.y, 0.0, 1.0);
                vColor = aColor;
            }
        """.trimIndent()

        val COLOR_FRAGMENT_SHADER = """
            #version 300 es
            precision mediump float;

            in vec4 vColor;
            out vec4 fragmentColor;

            void main() {
                fragmentColor = vColor;
            }
        """.trimIndent()
    }
}
