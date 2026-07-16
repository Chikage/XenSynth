package icu.ringona.xensynth.hexkeyboard.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.AttributeSet
import android.util.Log
import android.view.View
import androidx.compose.ui.graphics.Color
import icu.ringona.xensynth.hexkeyboard.core.AxialCoordinate
import icu.ringona.xensynth.hexkeyboard.core.HexKey
import icu.ringona.xensynth.hexkeyboard.core.HexaKeyboardLayout
import icu.ringona.xensynth.hexkeyboard.playback.KeyboardPlaybackNote
import icu.ringona.xensynth.hexkeyboard.playback.PlaybackKeyVisual
import icu.ringona.xensynth.hexkeyboard.playback.PlaybackVisualFrame
import icu.ringona.xensynth.view.OpenGlWaterfallView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

internal data class OpenGlHexKeyboardScene(
    val layout: HexaKeyboardLayout,
    val transform: KeyboardViewportTransform,
    val displayMode: KeyboardDisplayMode,
    val selectedCoordinates: Set<AxialCoordinate>,
    val selectionAnchorCoordinate: AxialCoordinate?,
    val activeForces: Map<AxialCoordinate, Float>,
    val playbackMode: Boolean,
    val playbackFrame: PlaybackVisualFrame,
    val playbackPositionSeconds: Double,
)

/** A dirty-rendered GLES surface used only for the visual half of the keyboard. */
internal class OpenGlHexKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {
    var onRendererFailure: ((Throwable) -> Unit)? = null

    private var rendererFailed = false
    private var rendererResumed = true
    private val keyboardRenderer = OpenGlHexKeyboardRenderer {
        post {
            if (!rendererFailed) {
                rendererFailed = true
                onRendererFailure?.invoke(it)
            }
        }
    }

    init {
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setEGLContextClientVersion(OPEN_GL_ES_MAJOR_VERSION)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        preserveEGLContextOnPause = true
        setRenderer(keyboardRenderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        isClickable = false
        isFocusable = false
    }

    fun setScene(scene: OpenGlHexKeyboardScene) {
        if (rendererFailed) return
        keyboardRenderer.updateScene(scene)
        requestRender()
    }

    override fun onDetachedFromWindow() {
        onRendererFailure = null
        keyboardRenderer.release()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) {
            if (!rendererResumed) {
                rendererResumed = true
                onResume()
                requestRender()
            }
        } else if (rendererResumed) {
            rendererResumed = false
            onPause()
        }
    }

    companion object {
        private const val OPEN_GL_ES_MAJOR_VERSION = 3

        fun isSupported(context: Context): Boolean = OpenGlWaterfallView.isSupported(context)
    }
}

/**
 * Converts immutable keyboard snapshots to batched triangles on the GLSurfaceView render thread.
 * Glyphs are rasterized once into an alpha atlas; every visible pixel is composed by GLES.
 */
private class OpenGlHexKeyboardRenderer(
    private val onFailure: (Throwable) -> Unit,
) : GLSurfaceView.Renderer {
    private val sceneReference = AtomicReference<OpenGlHexKeyboardScene?>(null)
    private val released = AtomicBoolean(false)
    private val failureDelivered = AtomicBoolean(false)

    private val baseGeometry = ColorGeometryBatch()
    private val effectGeometry = ColorGeometryBatch()
    private val keyLabels = TextureGeometryBatch()
    private val effectLabels = TextureGeometryBatch()
    private var colorUploadBuffer: FloatBuffer? = null
    private var textureUploadBuffer: FloatBuffer? = null

    private var viewportWidth = 1
    private var viewportHeight = 1
    private var glThread: Thread? = null
    private var glReady = false
    private var colorProgram = 0
    private var colorVertexArray = 0
    private var colorVertexBuffer = 0
    private var colorViewportLocation = -1
    private var textureProgram = 0
    private var textureVertexArray = 0
    private var textureVertexBuffer = 0
    private var textureViewportLocation = -1
    private var textureSamplerLocation = -1
    private var labelTexture = 0
    private var labelAtlas: LabelAtlas? = null

    fun updateScene(scene: OpenGlHexKeyboardScene) {
        if (released.get()) return
        sceneReference.set(
            scene.copy(
                selectedCoordinates = scene.selectedCoordinates.toSet(),
                activeForces = scene.activeForces.toMap(),
                playbackFrame = PlaybackVisualFrame(scene.playbackFrame.keys.toMap()),
            ),
        )
    }

    fun release() {
        released.set(true)
        sceneReference.set(null)
        if (Thread.currentThread() === glThread) releaseGlResources()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        glThread = Thread.currentThread()
        glReady = false
        clearCpuGeometry()
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        if (released.get()) {
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }

        failureDelivered.set(false)
        try {
            forgetGlResources()
            validateGlVersion()
            createGlResources()
            glReady = true
            checkGlError("surface creation")
        } catch (error: Throwable) {
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
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (released.get()) {
            if (glReady) releaseGlResources()
            return
        }
        if (!glReady) return

        try {
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glDisable(GLES30.GL_CULL_FACE)
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

            sceneReference.get()?.let { scene ->
                buildScene(scene)
                drawColorBatch(baseGeometry)
                drawTextureBatch(keyLabels)
                drawColorBatch(effectGeometry)
                drawTextureBatch(effectLabels)
            }
            checkGlError("frame rendering")
        } catch (error: Throwable) {
            glReady = false
            reportFailure(error)
        }
    }

    private fun buildScene(scene: OpenGlHexKeyboardScene) {
        clearCpuGeometry()
        val layout = scene.layout
        val transform = scene.transform
        val radius = (layout.configuration.radius.toFloat() - 1.5f) * transform.scale
        val rotation = layout.configuration.rotationDegrees.toFloat()
        val selectedPitch = scene.selectionAnchorCoordinate?.let(layout::cellAt)?.pitchClass

        appendOrigin(transform)
        layout.cells.forEach { key ->
            val center = transform.point(key.center)
            val activeForce = scene.activeForces[key.coordinate]
            val samePeriod = scene.displayMode == KeyboardDisplayMode.Period &&
                key.pitchClass == selectedPitch
            val tone = playbackTone(key, layout, scene.displayMode)
            val fill = if (scene.playbackMode) {
                tone.color(PLAYBACK_DIM_VALUE)
            } else {
                fillColor(key, layout, scene.displayMode, samePeriod)
            }
            baseGeometry.appendHexFill(center.x, center.y, radius, rotation, fill)

            val playbackVisual = scene.playbackFrame.keys[key.coordinate]
            if (scene.playbackMode && playbackVisual != null) {
                appendPlaybackFill(center.x, center.y, radius, rotation, tone, playbackVisual)
            }
            if (activeForce != null) {
                baseGeometry.appendHexFill(
                    center.x,
                    center.y,
                    radius,
                    rotation,
                    SELECTION.withAlpha(0.08f + activeForce * 0.20f),
                )
            }
            baseGeometry.appendHexRing(
                center.x,
                center.y,
                radius,
                rotation,
                max(1f, radius * 0.045f),
                LINE_DARK,
            )
            appendKeyLabel(
                key = key,
                centerX = center.x,
                centerY = center.y,
                radius = radius,
                mode = scene.displayMode,
                period = layout.configuration.period,
            )
        }

        if (!scene.playbackMode && scene.displayMode == KeyboardDisplayMode.Period) {
            appendPeriodVectors(scene)
        }
        if (scene.playbackMode) appendPlaybackEffects(scene, radius, rotation)
        appendSelectionOutlines(scene, radius, rotation)
    }

    private fun appendOrigin(transform: KeyboardViewportTransform) {
        val origin = transform.point(icu.ringona.xensynth.hexkeyboard.core.HexPoint(0.0, 0.0))
        val color = ACCENT.withAlpha(0.72f)
        baseGeometry.appendLine(origin.x - 8f, origin.y, origin.x + 8f, origin.y, 1.4f, color)
        baseGeometry.appendLine(origin.x, origin.y - 8f, origin.x, origin.y + 8f, 1.4f, color)
        baseGeometry.appendCircle(origin.x, origin.y, 2.5f, ACCENT)
    }

    private fun appendPlaybackFill(
        centerX: Float,
        centerY: Float,
        radius: Float,
        rotation: Float,
        tone: PlaybackTone,
        visual: PlaybackKeyVisual,
    ) {
        val flash = visual.flash.coerceIn(0f, 1f)
        if (visual.isActive) {
            baseGeometry.appendHexFill(
                centerX,
                centerY,
                radius,
                rotation,
                tone.color(PLAYBACK_ACTIVE_VALUE + flash * PLAYBACK_FLASH_VALUE_BOOST),
            )
            return
        }

        visual.upcoming?.let { upcoming ->
            val progress = upcoming.progress.coerceIn(0f, 1f)
            val sweep = progress * 360f
            if (sweep > 0.1f) {
                val outerValue = PLAYBACK_DIM_VALUE +
                    (PLAYBACK_PREVIEW_VALUE - PLAYBACK_DIM_VALUE) * progress
                val middleValue = PLAYBACK_DIM_VALUE +
                    (outerValue - PLAYBACK_DIM_VALUE) * 0.58f
                baseGeometry.appendClippedHexSector(
                    centerX = centerX,
                    centerY = centerY,
                    radius = radius,
                    rotationDegrees = rotation,
                    startDegrees = -90f - sweep / 2f,
                    sweepDegrees = sweep,
                    centerColor = tone.color(PLAYBACK_DIM_VALUE),
                    middleColor = tone.color(middleValue),
                    outerColor = tone.color(outerValue),
                )
            }
        }

        if (flash > 0.01f) {
            baseGeometry.appendHexFill(
                centerX,
                centerY,
                radius,
                rotation,
                tone.color(
                    PLAYBACK_ACTIVE_VALUE + flash * PLAYBACK_FLASH_VALUE_BOOST,
                    flash * 0.34f,
                ),
            )
        }
    }

    private fun appendPlaybackEffects(
        scene: OpenGlHexKeyboardScene,
        radius: Float,
        rotation: Float,
    ) {
        scene.playbackFrame.keys.forEach { (coordinate, visual) ->
            val key = scene.layout.cellAt(coordinate) ?: return@forEach
            val center = scene.transform.point(key.center)
            val tone = playbackTone(key, scene.layout, scene.displayMode)
            if (visual.isActive) {
                appendPlaybackTrackOutlines(center.x, center.y, radius, rotation, visual)
                appendActiveParticles(
                    center.x,
                    center.y,
                    radius,
                    tone,
                    visual,
                    scene.playbackPositionSeconds,
                )
            }
            if (visual.completedNotes.isNotEmpty()) {
                appendCompletedParticles(center.x, center.y, radius, tone, visual)
            }
        }
    }

    private fun appendPlaybackTrackOutlines(
        centerX: Float,
        centerY: Float,
        radius: Float,
        rotation: Float,
        visual: PlaybackKeyVisual,
    ) {
        val strokeWidth = max(1.35f, radius * 0.052f)
        val layerSpacing = max(strokeWidth * 1.62f, radius * 0.068f)
        visual.activeTracks.take(MAX_PLAYBACK_TRACK_LAYERS).forEachIndexed { index, track ->
            val layerRadius = max(radius * 0.42f, radius * 1.01f - index * layerSpacing)
            val color = trackColor(track)
            effectGeometry.appendHexRing(
                centerX,
                centerY,
                layerRadius,
                rotation,
                strokeWidth * 2.45f,
                color.withAlpha(0.20f + visual.flash.coerceIn(0f, 1f) * 0.18f),
            )
            effectGeometry.appendHexRing(
                centerX,
                centerY,
                layerRadius,
                rotation,
                strokeWidth,
                color.withAlpha(0.86f + visual.flash.coerceIn(0f, 1f) * 0.14f),
            )
        }
    }

    private fun appendActiveParticles(
        centerX: Float,
        centerY: Float,
        radius: Float,
        tone: PlaybackTone,
        visual: PlaybackKeyVisual,
        positionSeconds: Double,
    ) {
        val flash = visual.flash.coerceIn(0f, 1f)
        visual.activeNotes.take(MAX_ACTIVE_PARTICLE_NOTES).forEach { note ->
            val particleCount = if (note.repeatedHit || flash >= 0.34f) 6 else 2
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
                val x = centerX + cos(angle) * distance
                val y = centerY + sin(angle) * distance - radius * phase * 0.08f
                val alpha = (1f - phase) *
                    (0.24f + velocityRatio * 0.24f + flash * 0.38f)
                val particleRadius = max(
                    0.85f,
                    radius * (0.022f + deterministicUnit(seed xor 0x1020304) * 0.030f) *
                        (1f - phase * 0.42f),
                )
                effectGeometry.appendCircle(
                    x,
                    y,
                    particleRadius,
                    tone.color(0.82f + flash * 0.16f, alpha),
                )
            }
        }
    }

    private fun appendCompletedParticles(
        centerX: Float,
        centerY: Float,
        radius: Float,
        tone: PlaybackTone,
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
                val particleRadius = max(
                    0.75f,
                    radius * (0.025f + deterministicUnit(seed xor 0x55AA55A) * 0.045f) *
                        (1f - progress * 0.48f),
                )
                effectGeometry.appendCircle(
                    centerX + cos(direction) * distance,
                    centerY + sin(direction) * distance + gravity,
                    particleRadius,
                    tone.color(
                        0.84f + velocityRatio * 0.14f,
                        fade * (0.58f + velocityRatio * 0.34f),
                    ),
                )
            }
        }
    }

    private fun appendSelectionOutlines(
        scene: OpenGlHexKeyboardScene,
        radius: Float,
        rotation: Float,
    ) {
        scene.selectedCoordinates.forEach { coordinate ->
            val key = scene.layout.cellAt(coordinate) ?: return@forEach
            val force = scene.activeForces[coordinate]
            val strokeWidth = max(
                2.4f,
                radius * if (force == null) 0.12f else 0.12f + force * 0.08f,
            )
            val center = scene.transform.point(key.center)
            effectGeometry.appendHexRing(
                center.x,
                center.y,
                radius,
                rotation,
                strokeWidth + 2f,
                SELECTION.withAlpha(0.32f),
            )
            effectGeometry.appendHexRing(
                center.x,
                center.y,
                radius,
                rotation,
                strokeWidth,
                SELECTION,
            )
        }
    }

    private fun appendPeriodVectors(scene: OpenGlHexKeyboardScene) {
        val selected = scene.selectionAnchorCoordinate?.let(scene.layout::cellAt) ?: return
        val start = scene.transform.point(selected.center)
        scene.layout.periodVectors.forEachIndexed { index, vector ->
            val target = scene.layout.cellAt(
                AxialCoordinate(
                    q = selected.coordinate.q + vector.dq,
                    r = selected.coordinate.r + vector.dr,
                ),
            ) ?: return@forEachIndexed
            val end = scene.transform.point(target.center)
            effectGeometry.appendLine(
                start.x,
                start.y,
                end.x,
                end.y,
                2f,
                SELECTION.withAlpha(0.92f),
                rounded = true,
            )
            val angle = atan2(end.y - start.y, end.x - start.x)
            val spread = (PI / 6.0).toFloat()
            effectGeometry.appendLine(
                end.x,
                end.y,
                end.x - 8f * cos(angle - spread),
                end.y - 8f * sin(angle - spread),
                2f,
                SELECTION,
                rounded = true,
            )
            effectGeometry.appendLine(
                end.x,
                end.y,
                end.x - 8f * cos(angle + spread),
                end.y - 8f * sin(angle + spread),
                2f,
                SELECTION,
                rounded = true,
            )
            appendLabel(
                batch = effectLabels,
                text = "P${index + 1}",
                centerX = start.x * 0.45f + end.x * 0.55f,
                centerY = start.y * 0.45f + end.y * 0.55f - 12f,
                textSize = 11f,
                color = SELECTION,
            )
        }
    }

    private fun appendKeyLabel(
        key: HexKey,
        centerX: Float,
        centerY: Float,
        radius: Float,
        mode: KeyboardDisplayMode,
        period: Int,
    ) {
        if (radius < 5f || mode == KeyboardDisplayMode.Coordinates) return
        appendLabel(
            batch = keyLabels,
            text = hexKeyLabel(key, period),
            centerX = centerX,
            centerY = centerY,
            textSize = max(7f, radius * 0.43f),
            color = PRIMARY_DARK,
        )
    }

    private fun appendLabel(
        batch: TextureGeometryBatch,
        text: String,
        centerX: Float,
        centerY: Float,
        textSize: Float,
        color: Color4,
    ) {
        val atlas = labelAtlas ?: return
        val scale = textSize / LABEL_SOURCE_TEXT_SIZE
        val glyph = atlas.glyphs[text]
        if (glyph != null) {
            batch.appendLabelQuad(centerX, centerY, scale, glyph, color)
            return
        }

        val characterGlyphs = text.map { character ->
            atlas.glyphs[character.toString()] ?: return
        }
        val advance = textSize * LABEL_COMPOSITE_CHARACTER_ADVANCE_RATIO
        val firstCenterX = centerX - advance * (characterGlyphs.lastIndex / 2f)
        characterGlyphs.forEachIndexed { index, characterGlyph ->
            batch.appendLabelQuad(
                centerX = firstCenterX + advance * index,
                centerY = centerY,
                scale = scale,
                glyph = characterGlyph,
                color = color,
            )
        }
    }

    private fun TextureGeometryBatch.appendLabelQuad(
        centerX: Float,
        centerY: Float,
        scale: Float,
        glyph: AtlasGlyph,
        color: Color4,
    ) {
        appendQuad(
            left = centerX - LABEL_CELL_WIDTH * scale / 2f,
            top = centerY - LABEL_CELL_HEIGHT * scale / 2f,
            right = centerX + LABEL_CELL_WIDTH * scale / 2f,
            bottom = centerY + LABEL_CELL_HEIGHT * scale / 2f,
            glyph = glyph,
            color = color,
        )
    }

    private fun createGlResources() {
        colorProgram = linkProgram(COLOR_VERTEX_SHADER, COLOR_FRAGMENT_SHADER)
        colorViewportLocation = requireUniform(colorProgram, "uViewport")
        textureProgram = linkProgram(TEXTURE_VERTEX_SHADER, TEXTURE_FRAGMENT_SHADER)
        textureViewportLocation = requireUniform(textureProgram, "uViewport")
        textureSamplerLocation = requireUniform(textureProgram, "uTexture")

        val arrays = IntArray(2)
        GLES30.glGenVertexArrays(2, arrays, 0)
        colorVertexArray = arrays[0]
        textureVertexArray = arrays[1]
        requireGlHandle(colorVertexArray, "color vertex array")
        requireGlHandle(textureVertexArray, "texture vertex array")

        val buffers = IntArray(2)
        GLES30.glGenBuffers(2, buffers, 0)
        colorVertexBuffer = buffers[0]
        textureVertexBuffer = buffers[1]
        requireGlHandle(colorVertexBuffer, "color vertex buffer")
        requireGlHandle(textureVertexBuffer, "texture vertex buffer")

        GLES30.glBindVertexArray(colorVertexArray)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, colorVertexBuffer)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, COLOR_VERTEX_BYTES, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(
            1,
            4,
            GLES30.GL_FLOAT,
            false,
            COLOR_VERTEX_BYTES,
            2 * Float.SIZE_BYTES,
        )

        GLES30.glBindVertexArray(textureVertexArray)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, textureVertexBuffer)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, TEXTURE_VERTEX_BYTES, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(
            1,
            2,
            GLES30.GL_FLOAT,
            false,
            TEXTURE_VERTEX_BYTES,
            2 * Float.SIZE_BYTES,
        )
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(
            2,
            4,
            GLES30.GL_FLOAT,
            false,
            TEXTURE_VERTEX_BYTES,
            4 * Float.SIZE_BYTES,
        )
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        val atlas = createLabelAtlas()
        labelAtlas = atlas
        labelTexture = uploadLabelAtlas(atlas.bitmap)
        atlas.bitmap.recycle()
    }

    private fun drawColorBatch(batch: ColorGeometryBatch) {
        if (batch.vertexCount == 0) return
        val upload = ensureColorUploadBuffer(batch.floatCount)
        upload.clear()
        upload.put(batch.values, 0, batch.floatCount)
        upload.flip()
        GLES30.glUseProgram(colorProgram)
        GLES30.glUniform2f(colorViewportLocation, viewportWidth.toFloat(), viewportHeight.toFloat())
        GLES30.glBindVertexArray(colorVertexArray)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, colorVertexBuffer)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            batch.floatCount * Float.SIZE_BYTES,
            upload,
            GLES30.GL_STREAM_DRAW,
        )
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, batch.vertexCount)
        GLES30.glBindVertexArray(0)
    }

    private fun drawTextureBatch(batch: TextureGeometryBatch) {
        if (batch.vertexCount == 0 || labelTexture == 0) return
        val upload = ensureTextureUploadBuffer(batch.floatCount)
        upload.clear()
        upload.put(batch.values, 0, batch.floatCount)
        upload.flip()
        GLES30.glUseProgram(textureProgram)
        GLES30.glUniform2f(textureViewportLocation, viewportWidth.toFloat(), viewportHeight.toFloat())
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, labelTexture)
        GLES30.glUniform1i(textureSamplerLocation, 0)
        GLES30.glBindVertexArray(textureVertexArray)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, textureVertexBuffer)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            batch.floatCount * Float.SIZE_BYTES,
            upload,
            GLES30.GL_STREAM_DRAW,
        )
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, batch.vertexCount)
        GLES30.glBindVertexArray(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    private fun createLabelAtlas(): LabelAtlas {
        val labels = buildList {
            repeat(MAX_LABEL_NUMBER + 1) { add(it.toString()) }
            add("P1")
            add("P2")
            add("C")
            add("-")
        }
        val rows = ceil(labels.size / LABEL_ATLAS_COLUMNS.toFloat()).roundToInt()
        val width = LABEL_CELL_WIDTH * LABEL_ATLAS_COLUMNS
        val height = LABEL_CELL_HEIGHT * rows
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = LABEL_SOURCE_TEXT_SIZE
        }
        val baselineOffset = -(paint.ascent() + paint.descent()) / 2f
        val glyphs = HashMap<String, AtlasGlyph>(labels.size)
        labels.forEachIndexed { index, label ->
            val column = index % LABEL_ATLAS_COLUMNS
            val row = index / LABEL_ATLAS_COLUMNS
            val left = column * LABEL_CELL_WIDTH
            val top = row * LABEL_CELL_HEIGHT
            canvas.drawText(
                label,
                left + LABEL_CELL_WIDTH / 2f,
                top + LABEL_CELL_HEIGHT / 2f + baselineOffset,
                paint,
            )
            glyphs[label] = AtlasGlyph(
                u0 = left.toFloat() / width,
                v0 = top.toFloat() / height,
                u1 = (left + LABEL_CELL_WIDTH).toFloat() / width,
                v1 = (top + LABEL_CELL_HEIGHT).toFloat() / height,
            )
        }
        return LabelAtlas(bitmap, glyphs)
    }

    private fun uploadLabelAtlas(bitmap: Bitmap): Int {
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        val texture = textures[0]
        requireGlHandle(texture, "label texture")
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return texture
    }

    private fun ensureColorUploadBuffer(floatCount: Int): FloatBuffer {
        val current = colorUploadBuffer
        if (current != null && current.capacity() >= floatCount) return current
        return allocateUploadBuffer(floatCount).also { colorUploadBuffer = it }
    }

    private fun ensureTextureUploadBuffer(floatCount: Int): FloatBuffer {
        val current = textureUploadBuffer
        if (current != null && current.capacity() >= floatCount) return current
        return allocateUploadBuffer(floatCount).also { textureUploadBuffer = it }
    }

    private fun allocateUploadBuffer(required: Int): FloatBuffer {
        var capacity = INITIAL_UPLOAD_FLOAT_CAPACITY
        while (capacity < required) capacity *= 2
        return ByteBuffer.allocateDirect(capacity * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    }

    private fun validateGlVersion() {
        val version = GLES30.glGetString(GLES30.GL_VERSION).orEmpty()
        val major = OPEN_GL_ES_VERSION.find(version)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        check(major >= 3) { "OpenGL ES 3.0 required, reported version='$version'" }
        Log.i(
            TAG,
            "GL initialized vendor='${GLES30.glGetString(GLES30.GL_VENDOR).orEmpty()}' " +
                "renderer='${GLES30.glGetString(GLES30.GL_RENDERER).orEmpty()}' version='$version'",
        )
    }

    private fun linkProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragment = try {
            compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        } catch (error: Throwable) {
            GLES30.glDeleteShader(vertex)
            throw error
        }
        val program = GLES30.glCreateProgram()
        requireGlHandle(program, "program")
        try {
            GLES30.glAttachShader(program, vertex)
            GLES30.glAttachShader(program, fragment)
            GLES30.glLinkProgram(program)
            val status = IntArray(1)
            GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES30.GL_TRUE) {
                "OpenGL program link failed: ${GLES30.glGetProgramInfoLog(program).orEmpty()}"
            }
            return program
        } catch (error: Throwable) {
            GLES30.glDeleteProgram(program)
            throw error
        } finally {
            GLES30.glDetachShader(program, vertex)
            GLES30.glDetachShader(program, fragment)
            GLES30.glDeleteShader(vertex)
            GLES30.glDeleteShader(fragment)
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        requireGlHandle(shader, "shader")
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES30.GL_TRUE) {
            val info = GLES30.glGetShaderInfoLog(shader).orEmpty()
            GLES30.glDeleteShader(shader)
            error("OpenGL shader compile failed type=$type: $info")
        }
        return shader
    }

    private fun requireUniform(program: Int, name: String): Int {
        return GLES30.glGetUniformLocation(program, name).also {
            check(it >= 0) { "Required OpenGL uniform '$name' was not found" }
        }
    }

    private fun requireGlHandle(handle: Int, label: String) {
        check(handle != 0) { "Unable to create OpenGL $label" }
    }

    private fun checkGlError(operation: String) {
        val errors = ArrayList<String>(2)
        var error = GLES30.glGetError()
        while (error != GLES30.GL_NO_ERROR && errors.size < 4) {
            errors += "0x${Integer.toHexString(error)}"
            error = GLES30.glGetError()
        }
        check(errors.isEmpty()) { "OpenGL error after $operation: ${errors.joinToString()}" }
    }

    private fun releaseGlResources() {
        if (labelTexture != 0) GLES30.glDeleteTextures(1, intArrayOf(labelTexture), 0)
        if (colorVertexBuffer != 0 || textureVertexBuffer != 0) {
            GLES30.glDeleteBuffers(2, intArrayOf(colorVertexBuffer, textureVertexBuffer), 0)
        }
        if (colorVertexArray != 0 || textureVertexArray != 0) {
            GLES30.glDeleteVertexArrays(2, intArrayOf(colorVertexArray, textureVertexArray), 0)
        }
        if (colorProgram != 0) GLES30.glDeleteProgram(colorProgram)
        if (textureProgram != 0) GLES30.glDeleteProgram(textureProgram)
        forgetGlResources()
    }

    private fun forgetGlResources() {
        colorProgram = 0
        colorVertexArray = 0
        colorVertexBuffer = 0
        colorViewportLocation = -1
        textureProgram = 0
        textureVertexArray = 0
        textureVertexBuffer = 0
        textureViewportLocation = -1
        textureSamplerLocation = -1
        labelTexture = 0
        labelAtlas = null
        glReady = false
    }

    private fun clearCpuGeometry() {
        baseGeometry.clear()
        effectGeometry.clear()
        keyLabels.clear()
        effectLabels.clear()
    }

    private fun reportFailure(error: Throwable) {
        Log.e(TAG, "OpenGL hex keyboard renderer failed", error)
        if (failureDelivered.compareAndSet(false, true)) onFailure(error)
    }

    private data class PlaybackTone(val hue: Float, val saturation: Float) {
        fun color(value: Float, alpha: Float = 1f): Color4 =
            hsvColor(hue, saturation, value.coerceIn(0f, 1f), alpha.coerceIn(0f, 1f))
    }

    private fun playbackTone(
        key: HexKey,
        layout: HexaKeyboardLayout,
        mode: KeyboardDisplayMode,
    ): PlaybackTone {
        val (index, count) = when (mode) {
            KeyboardDisplayMode.Coordinates ->
                Math.floorMod(key.coordinate.q * 2 + key.coordinate.r * 3, 9) to 9
            KeyboardDisplayMode.Pitch,
            KeyboardDisplayMode.Period -> key.pitchClass to max(1, layout.configuration.period)
        }
        val saturation = if (mode == KeyboardDisplayMode.Coordinates) 0.38f else 0.62f
        return PlaybackTone(toneHue(index, count), saturation)
    }

    private fun fillColor(
        key: HexKey,
        layout: HexaKeyboardLayout,
        mode: KeyboardDisplayMode,
        samePeriod: Boolean,
    ): Color4 = when (mode) {
        KeyboardDisplayMode.Coordinates -> hsvColor(
            toneHue(Math.floorMod(key.coordinate.q * 2 + key.coordinate.r * 3, 9), 9),
            0.38f,
            0.34f,
        )
        KeyboardDisplayMode.Pitch -> hsvColor(
            toneHue(key.pitchClass, max(1, layout.configuration.period)),
            0.62f,
            0.43f,
        )
        KeyboardDisplayMode.Period -> hsvColor(
            toneHue(key.pitchClass, max(1, layout.configuration.period)),
            if (samePeriod) 0.70f else 0.32f,
            if (samePeriod) 0.48f else 0.27f,
        )
    }

    private fun toneHue(index: Int, count: Int): Float {
        val safeCount = max(1, count)
        return Math.floorMod(index, safeCount).toFloat() / safeCount * 360f
    }

    private fun trackColor(track: Int): Color4 = hsvColor(
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

    private fun playbackParticleSeed(note: KeyboardPlaybackNote, index: Int, salt: Int): Int =
        note.scoreIndex * 73_856_093 xor
            note.track * 19_349_663 xor
            note.coordinate.q * 83_492_791 xor
            note.coordinate.r * 49_979_687 xor
            index * 961_748_927 xor salt

    private fun deterministicUnit(seed: Int): Float {
        var value = seed
        value = value xor (value ushr 16)
        value *= -2_048_144_789
        value = value xor (value ushr 13)
        value *= -1_028_477_387
        value = value xor (value ushr 16)
        return ((value ushr 8) and 0x00FF_FFFF) / 16_777_215f
    }

    private data class LabelAtlas(val bitmap: Bitmap, val glyphs: Map<String, AtlasGlyph>)
    private data class AtlasGlyph(val u0: Float, val v0: Float, val u1: Float, val v1: Float)

    private data class Color4(
        val red: Float,
        val green: Float,
        val blue: Float,
        val alpha: Float = 1f,
    ) {
        fun withAlpha(nextAlpha: Float) = copy(alpha = nextAlpha.coerceIn(0f, 1f))
    }

    private class ColorGeometryBatch {
        var values = FloatArray(INITIAL_GEOMETRY_FLOAT_CAPACITY)
            private set
        var floatCount = 0
            private set
        val vertexCount: Int get() = floatCount / COLOR_FLOATS_PER_VERTEX

        fun clear() {
            floatCount = 0
        }

        fun appendHexFill(
            centerX: Float,
            centerY: Float,
            radius: Float,
            rotationDegrees: Float,
            color: Color4,
        ) {
            if (radius <= 0f || color.alpha <= 0f) return
            repeat(6) { index ->
                val first = polar(centerX, centerY, radius, rotationDegrees + index * 60f)
                val second = polar(centerX, centerY, radius, rotationDegrees + (index + 1) * 60f)
                appendTriangle(centerX, centerY, color, first.x, first.y, color, second.x, second.y, color)
            }
        }

        fun appendHexRing(
            centerX: Float,
            centerY: Float,
            radius: Float,
            rotationDegrees: Float,
            width: Float,
            color: Color4,
        ) {
            if (radius <= 0f || width <= 0f || color.alpha <= 0f) return
            val outer = radius + width / 2f
            val inner = max(0f, radius - width / 2f)
            repeat(6) { index ->
                val outerA = polar(centerX, centerY, outer, rotationDegrees + index * 60f)
                val outerB = polar(centerX, centerY, outer, rotationDegrees + (index + 1) * 60f)
                val innerA = polar(centerX, centerY, inner, rotationDegrees + index * 60f)
                val innerB = polar(centerX, centerY, inner, rotationDegrees + (index + 1) * 60f)
                appendQuad(outerA, outerB, innerA, innerB, color)
            }
        }

        fun appendClippedHexSector(
            centerX: Float,
            centerY: Float,
            radius: Float,
            rotationDegrees: Float,
            startDegrees: Float,
            sweepDegrees: Float,
            centerColor: Color4,
            middleColor: Color4,
            outerColor: Color4,
        ) {
            val segments = max(1, ceil(kotlin.math.abs(sweepDegrees) / 8f).toInt())
            val arcRadius = radius * 1.08f
            repeat(segments) { index ->
                val angleA = startDegrees + sweepDegrees * index / segments
                val angleB = startDegrees + sweepDegrees * (index + 1) / segments
                val edgeA = hexRayRadius(radius, rotationDegrees, angleA)
                val edgeB = hexRayRadius(radius, rotationDegrees, angleB)
                val middleRadius = arcRadius * 0.5f
                val midA = polar(centerX, centerY, min(edgeA, middleRadius), angleA)
                val midB = polar(centerX, centerY, min(edgeB, middleRadius), angleB)
                appendTriangle(
                    centerX,
                    centerY,
                    centerColor,
                    midA.x,
                    midA.y,
                    middleColor,
                    midB.x,
                    midB.y,
                    middleColor,
                )
                if (edgeA > middleRadius || edgeB > middleRadius) {
                    val outerA = polar(centerX, centerY, edgeA, angleA)
                    val outerB = polar(centerX, centerY, edgeB, angleB)
                    appendQuadGradient(
                        midA,
                        midB,
                        outerA,
                        outerB,
                        middleColor,
                        outerColor,
                    )
                }
            }
        }

        fun appendLine(
            startX: Float,
            startY: Float,
            endX: Float,
            endY: Float,
            width: Float,
            color: Color4,
            rounded: Boolean = false,
        ) {
            val dx = endX - startX
            val dy = endY - startY
            val length = kotlin.math.sqrt(dx * dx + dy * dy)
            if (length <= 0.0001f || width <= 0f || color.alpha <= 0f) return
            val nx = -dy / length * width / 2f
            val ny = dx / length * width / 2f
            appendQuad(
                Point(startX + nx, startY + ny),
                Point(endX + nx, endY + ny),
                Point(startX - nx, startY - ny),
                Point(endX - nx, endY - ny),
                color,
            )
            if (rounded) {
                appendCircle(startX, startY, width / 2f, color)
                appendCircle(endX, endY, width / 2f, color)
            }
        }

        fun appendCircle(centerX: Float, centerY: Float, radius: Float, color: Color4) {
            if (radius <= 0f || color.alpha <= 0f) return
            val segments = if (radius < 2f) 10 else 18
            repeat(segments) { index ->
                val first = polar(centerX, centerY, radius, index * 360f / segments)
                val second = polar(centerX, centerY, radius, (index + 1) * 360f / segments)
                appendTriangle(centerX, centerY, color, first.x, first.y, color, second.x, second.y, color)
            }
        }

        private fun appendQuad(a: Point, b: Point, c: Point, d: Point, color: Color4) {
            appendTriangle(a.x, a.y, color, c.x, c.y, color, b.x, b.y, color)
            appendTriangle(b.x, b.y, color, c.x, c.y, color, d.x, d.y, color)
        }

        private fun appendQuadGradient(
            innerA: Point,
            innerB: Point,
            outerA: Point,
            outerB: Point,
            innerColor: Color4,
            outerColor: Color4,
        ) {
            appendTriangle(
                innerA.x,
                innerA.y,
                innerColor,
                outerA.x,
                outerA.y,
                outerColor,
                innerB.x,
                innerB.y,
                innerColor,
            )
            appendTriangle(
                innerB.x,
                innerB.y,
                innerColor,
                outerA.x,
                outerA.y,
                outerColor,
                outerB.x,
                outerB.y,
                outerColor,
            )
        }

        private fun appendTriangle(
            ax: Float,
            ay: Float,
            aColor: Color4,
            bx: Float,
            by: Float,
            bColor: Color4,
            cx: Float,
            cy: Float,
            cColor: Color4,
        ) {
            ensureCapacity(COLOR_FLOATS_PER_VERTEX * 3)
            appendVertex(ax, ay, aColor)
            appendVertex(bx, by, bColor)
            appendVertex(cx, cy, cColor)
        }

        private fun appendVertex(x: Float, y: Float, color: Color4) {
            values[floatCount++] = x
            values[floatCount++] = y
            values[floatCount++] = color.red
            values[floatCount++] = color.green
            values[floatCount++] = color.blue
            values[floatCount++] = color.alpha
        }

        private fun ensureCapacity(additional: Int) {
            if (floatCount + additional <= values.size) return
            var size = values.size
            while (size < floatCount + additional) size *= 2
            values = values.copyOf(size)
        }
    }

    private class TextureGeometryBatch {
        var values = FloatArray(INITIAL_TEXTURE_FLOAT_CAPACITY)
            private set
        var floatCount = 0
            private set
        val vertexCount: Int get() = floatCount / TEXTURE_FLOATS_PER_VERTEX

        fun clear() {
            floatCount = 0
        }

        fun appendQuad(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            glyph: AtlasGlyph,
            color: Color4,
        ) {
            ensureCapacity(TEXTURE_FLOATS_PER_VERTEX * 6)
            appendVertex(left, top, glyph.u0, glyph.v0, color)
            appendVertex(left, bottom, glyph.u0, glyph.v1, color)
            appendVertex(right, top, glyph.u1, glyph.v0, color)
            appendVertex(right, top, glyph.u1, glyph.v0, color)
            appendVertex(left, bottom, glyph.u0, glyph.v1, color)
            appendVertex(right, bottom, glyph.u1, glyph.v1, color)
        }

        private fun appendVertex(x: Float, y: Float, u: Float, v: Float, color: Color4) {
            values[floatCount++] = x
            values[floatCount++] = y
            values[floatCount++] = u
            values[floatCount++] = v
            values[floatCount++] = color.red
            values[floatCount++] = color.green
            values[floatCount++] = color.blue
            values[floatCount++] = color.alpha
        }

        private fun ensureCapacity(additional: Int) {
            if (floatCount + additional <= values.size) return
            var size = values.size
            while (size < floatCount + additional) size *= 2
            values = values.copyOf(size)
        }
    }

    private data class Point(val x: Float, val y: Float)

    companion object {
        private const val TAG = "OpenGlHexKeyboard"
        private const val COLOR_FLOATS_PER_VERTEX = 6
        private const val TEXTURE_FLOATS_PER_VERTEX = 8
        private const val COLOR_VERTEX_BYTES = COLOR_FLOATS_PER_VERTEX * Float.SIZE_BYTES
        private const val TEXTURE_VERTEX_BYTES = TEXTURE_FLOATS_PER_VERTEX * Float.SIZE_BYTES
        private const val INITIAL_GEOMETRY_FLOAT_CAPACITY = 32_768
        private const val INITIAL_TEXTURE_FLOAT_CAPACITY = 16_384
        private const val INITIAL_UPLOAD_FLOAT_CAPACITY = 32_768
        private const val LABEL_SOURCE_TEXT_SIZE = 48f
        private const val LABEL_CELL_WIDTH = 112
        private const val LABEL_CELL_HEIGHT = 72
        private const val LABEL_ATLAS_COLUMNS = 9
        private const val MAX_LABEL_NUMBER = 199
        private const val LABEL_COMPOSITE_CHARACTER_ADVANCE_RATIO = 0.62f
        private const val PLAYBACK_TRACK_GOLDEN_ANGLE = 137.508f
        private const val PLAYBACK_DIM_VALUE = 0.22f
        private const val PLAYBACK_PREVIEW_VALUE = 0.76f
        private const val PLAYBACK_ACTIVE_VALUE = 0.88f
        private const val PLAYBACK_FLASH_VALUE_BOOST = 0.26f
        private const val MAX_PLAYBACK_TRACK_LAYERS = 8
        private const val MAX_ACTIVE_PARTICLE_NOTES = 3
        private const val ACTIVE_PARTICLE_SALT = 0x1A2B3C4D
        private const val COMPLETED_PARTICLE_SALT = 0x4D3C2B1A

        private val OPEN_GL_ES_VERSION = Regex("OpenGL ES\\s+(\\d+)(?:\\.(\\d+))?")
        private val PLAYBACK_TRACK_HUES =
            floatArrayOf(190f, 28f, 132f, 48f, 264f, 158f, 330f, 88f)
        private val LINE_DARK = HexaPalette.LineDark.toColor4()
        private val PRIMARY_DARK = HexaPalette.PrimaryDark.toColor4()
        private val ACCENT = HexaPalette.Accent.toColor4()
        private val SELECTION = HexaPalette.Selection.toColor4()

        private fun Color.toColor4(): Color4 = Color4(red, green, blue, alpha)

        private fun hsvColor(
            hue: Float,
            saturation: Float,
            value: Float,
            alpha: Float = 1f,
        ): Color4 {
            val normalizedHue = ((hue % 360f) + 360f) % 360f
            val chroma = value * saturation
            val hueSection = normalizedHue / 60f
            val second = chroma * (1f - kotlin.math.abs(hueSection % 2f - 1f))
            val (red, green, blue) = when (hueSection.toInt()) {
                0 -> Triple(chroma, second, 0f)
                1 -> Triple(second, chroma, 0f)
                2 -> Triple(0f, chroma, second)
                3 -> Triple(0f, second, chroma)
                4 -> Triple(second, 0f, chroma)
                else -> Triple(chroma, 0f, second)
            }
            val match = value - chroma
            return Color4(red + match, green + match, blue + match, alpha.coerceIn(0f, 1f))
        }

        private fun polar(
            centerX: Float,
            centerY: Float,
            radius: Float,
            degrees: Float,
        ): Point {
            val radians = degrees * PI.toFloat() / 180f
            return Point(centerX + radius * cos(radians), centerY + radius * sin(radians))
        }

        private fun hexRayRadius(radius: Float, rotationDegrees: Float, rayDegrees: Float): Float {
            val normalBase = rotationDegrees + 30f
            val nearestNormal = kotlin.math.round((rayDegrees - normalBase) / 60f) * 60f + normalBase
            val difference = (rayDegrees - nearestNormal) * PI.toFloat() / 180f
            return radius * cos(PI.toFloat() / 6f) / cos(difference)
        }

        private val COLOR_VERTEX_SHADER = """
            #version 300 es
            precision highp float;
            layout(location = 0) in vec2 aPosition;
            layout(location = 1) in vec4 aColor;
            uniform vec2 uViewport;
            out vec4 vColor;
            void main() {
                vec2 clip = aPosition / max(uViewport, vec2(1.0)) * 2.0 - 1.0;
                gl_Position = vec4(clip.x, -clip.y, 0.0, 1.0);
                vColor = aColor;
            }
        """.trimIndent()

        private val COLOR_FRAGMENT_SHADER = """
            #version 300 es
            precision mediump float;
            in vec4 vColor;
            out vec4 fragmentColor;
            void main() { fragmentColor = vColor; }
        """.trimIndent()

        private val TEXTURE_VERTEX_SHADER = """
            #version 300 es
            precision highp float;
            layout(location = 0) in vec2 aPosition;
            layout(location = 1) in vec2 aUv;
            layout(location = 2) in vec4 aColor;
            uniform vec2 uViewport;
            out vec2 vUv;
            out vec4 vColor;
            void main() {
                vec2 clip = aPosition / max(uViewport, vec2(1.0)) * 2.0 - 1.0;
                gl_Position = vec4(clip.x, -clip.y, 0.0, 1.0);
                vUv = aUv;
                vColor = aColor;
            }
        """.trimIndent()

        private val TEXTURE_FRAGMENT_SHADER = """
            #version 300 es
            precision mediump float;
            in vec2 vUv;
            in vec4 vColor;
            uniform sampler2D uTexture;
            out vec4 fragmentColor;
            void main() {
                float coverage = texture(uTexture, vUv).a;
                fragmentColor = vec4(vColor.rgb, vColor.a * coverage);
            }
        """.trimIndent()
    }
}
