package icu.ringona.xensynth.view

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import com.qmdeve.blurview.BlurNative
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin

internal object FrostedGlassStyle {
    const val TOOLBAR_BLUR_RADIUS_DP = 40f
    const val TOOLBAR_LEGACY_DOWNSAMPLE = 2.5f
    const val TOOLBAR_LEGACY_BLUR_ROUNDS = 3
    const val TOOLBAR_REFRACTION_MAGNIFICATION = 1.035f
    const val RULER_BLUR_RADIUS = 42f
    const val LEGACY_DOWNSAMPLE = 6f
    const val LEGACY_BLUR_ROUNDS = 2

    const val TOOLBAR_TOP_ALPHA = 54
    const val TOOLBAR_BOTTOM_ALPHA = 92
    const val TOOLBAR_HAIRLINE_ALPHA = 112

    const val RULER_TOP_ALPHA = 42
    const val RULER_BOTTOM_ALPHA = 104
    const val RULER_HAIRLINE_ALPHA = 168
    const val RULER_SHADOW_ALPHA = 58

    fun darkTop(alpha: Int): Int = Color.argb(alpha, 28, 34, 43)
    fun darkBottom(alpha: Int): Int = Color.argb(alpha, 7, 9, 14)
    fun highlight(alpha: Int): Int = Color.argb(alpha, 255, 255, 255)
    fun coolHighlight(alpha: Int): Int = Color.argb(alpha, 220, 236, 255)
    fun shadow(alpha: Int): Int = Color.argb(alpha, 0, 0, 0)
    fun toolbarBlurRadiusPx(density: Float): Float = TOOLBAR_BLUR_RADIUS_DP * density
}

internal fun applyNativeFrostedGlass(view: View, blurRadius: Float) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        applyNativeFrostedGlassS(view, blurRadius)
    }
}

@RequiresApi(Build.VERSION_CODES.S)
private fun applyNativeFrostedGlassS(view: View, blurRadius: Float) {
    view.setRenderEffect(
        RenderEffect.createBlurEffect(
            blurRadius,
            blurRadius,
            Shader.TileMode.CLAMP
        )
    )
}

internal class LegacyFrostedGlassBlur(
    private val downsample: Float = FrostedGlassStyle.LEGACY_DOWNSAMPLE,
    blurRounds: Int = FrostedGlassStyle.LEGACY_BLUR_ROUNDS
) {
    private val blurDelegate = lazy {
        BlurNative().apply {
            setBlurRounds(blurRounds)
        }
    }
    private val blur by blurDelegate
    private var sourceBitmap: Bitmap? = null
    private var blurredBitmap: Bitmap? = null
    private var blurCanvas: Canvas? = null
    private var lastSignature: Long = Long.MIN_VALUE

    fun drawBlurredViewSnapshot(
        canvas: Canvas,
        target: View,
        sourceView: View,
        area: RectF,
        radius: Float,
        magnification: Float = 1f
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            area.width() <= 1f ||
            area.height() <= 1f
        ) {
            return false
        }
        if (sourceView.width <= 0 || sourceView.height <= 0 || target.width <= 0 || target.height <= 0) {
            return false
        }
        val scaledWidth = max(1, ceil(area.width() / downsample).toInt())
        val scaledHeight = max(1, ceil(area.height() / downsample).toInt())
        ensureBitmaps(scaledWidth, scaledHeight)
        val source = sourceBitmap ?: return false
        val blurred = blurredBitmap ?: return false
        val offscreen = blurCanvas ?: return false
        val signature = viewSnapshotSignature(target, sourceView, area, scaledWidth, scaledHeight)
        if (signature != lastSignature) {
            source.eraseColor(Color.TRANSPARENT)
            val targetLocation = IntArray(2)
            val sourceLocation = IntArray(2)
            target.getLocationInWindow(targetLocation)
            sourceView.getLocationInWindow(sourceLocation)
            val saveCount = offscreen.save()
            offscreen.scale(scaledWidth / area.width(), scaledHeight / area.height())
            if (magnification != 1f) {
                offscreen.scale(magnification, magnification, area.width() * 0.5f, area.height() * 0.5f)
            }
            offscreen.translate(
                sourceLocation[0] - targetLocation[0] - area.left,
                sourceLocation[1] - targetLocation[1] - area.top
            )
            runCatching {
                sourceView.draw(offscreen)
            }
            offscreen.restoreToCount(saveCount)
            if (blur.prepare(source, radius / downsample)) {
                blur.blur(source, blurred)
                lastSignature = signature
            }
        }
        canvas.drawBitmap(
            blurred,
            Rect(0, 0, blurred.width, blurred.height),
            area,
            null
        )
        return true
    }

    fun drawBlurredBitmapRegion(
        canvas: Canvas,
        sourceBitmap: Bitmap?,
        area: RectF,
        radius: Float
    ): Boolean {
        val source = sourceBitmap ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ||
            source.isRecycled ||
            area.width() <= 1f ||
            area.height() <= 1f
        ) {
            return false
        }
        val scaledWidth = max(1, ceil(area.width() / downsample).toInt())
        val scaledHeight = max(1, ceil(area.height() / downsample).toInt())
        ensureBitmaps(scaledWidth, scaledHeight)
        val downsampled = this.sourceBitmap ?: return false
        val blurred = blurredBitmap ?: return false
        val offscreen = blurCanvas ?: return false
        downsampled.eraseColor(Color.TRANSPARENT)
        val saveCount = offscreen.save()
        offscreen.scale(scaledWidth / area.width(), scaledHeight / area.height())
        offscreen.translate(-area.left, -area.top)
        offscreen.drawBitmap(source, 0f, 0f, null)
        offscreen.restoreToCount(saveCount)
        if (!blur.prepare(downsampled, radius / downsample)) {
            return false
        }
        blur.blur(downsampled, blurred)
        canvas.drawBitmap(
            blurred,
            Rect(0, 0, blurred.width, blurred.height),
            area,
            null
        )
        return true
    }

    fun release() {
        sourceBitmap?.recycle()
        blurredBitmap?.recycle()
        sourceBitmap = null
        blurredBitmap = null
        blurCanvas = null
        if (blurDelegate.isInitialized()) {
            blur.release()
        }
    }

    private fun ensureBitmaps(width: Int, height: Int) {
        val source = sourceBitmap
        val blurred = blurredBitmap
        if (source?.width == width && source.height == height &&
            blurred?.width == width && blurred.height == height
        ) {
            return
        }
        source?.recycle()
        blurred?.recycle()
        sourceBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        blurredBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        blurCanvas = Canvas(sourceBitmap!!)
        lastSignature = Long.MIN_VALUE
    }

    private fun viewSnapshotSignature(
        target: View,
        sourceView: View,
        area: RectF,
        width: Int,
        height: Int
    ): Long {
        var result = 17L
        result = 31L * result + target.width
        result = 31L * result + target.height
        result = 31L * result + sourceView.width
        result = 31L * result + sourceView.height
        result = 31L * result + area.left.roundToInt()
        result = 31L * result + area.top.roundToInt()
        result = 31L * result + area.right.roundToInt()
        result = 31L * result + area.bottom.roundToInt()
        result = 31L * result + width
        result = 31L * result + height
        result = 31L * result + sourceView.drawingTime
        return result
    }
}

internal interface FrostedGlassBlur {
    fun drawBlurredViewSnapshot(
        canvas: Canvas,
        target: View,
        sourceView: View,
        area: RectF,
        radius: Float,
        magnification: Float = 1f
    ): Boolean

    fun drawBlurredBitmapRegion(
        canvas: Canvas,
        sourceBitmap: Bitmap?,
        area: RectF,
        radius: Float
    ): Boolean
}

@RequiresApi(Build.VERSION_CODES.S)
private class NativeFrostedGlassBlur : FrostedGlassBlur {
    private var renderNode: RenderNode? = null

    override fun drawBlurredViewSnapshot(
        canvas: Canvas,
        target: View,
        sourceView: View,
        area: RectF,
        radius: Float,
        magnification: Float
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            area.width() <= 1f ||
            area.height() <= 1f ||
            sourceView.width <= 0 ||
            sourceView.height <= 0
        ) {
            return false
        }
        val targetLocation = IntArray(2)
        val sourceLocation = IntArray(2)
        target.getLocationInWindow(targetLocation)
        sourceView.getLocationInWindow(sourceLocation)
        return drawBlurredNode(canvas, area, radius) { recordingCanvas ->
            if (magnification != 1f) {
                recordingCanvas.scale(magnification, magnification, area.width() * 0.5f, area.height() * 0.5f)
            }
            recordingCanvas.translate(
                sourceLocation[0] - targetLocation[0] - area.left,
                sourceLocation[1] - targetLocation[1] - area.top
            )
            sourceView.draw(recordingCanvas)
        }
    }

    override fun drawBlurredBitmapRegion(
        canvas: Canvas,
        sourceBitmap: Bitmap?,
        area: RectF,
        radius: Float
    ): Boolean {
        val source = sourceBitmap ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            source.isRecycled ||
            area.width() <= 1f ||
            area.height() <= 1f
        ) {
            return false
        }
        return drawBlurredNode(canvas, area, radius) { recordingCanvas ->
            recordingCanvas.translate(-area.left, -area.top)
            recordingCanvas.drawBitmap(source, 0f, 0f, null)
        }
    }

    private fun drawBlurredNode(
        canvas: Canvas,
        area: RectF,
        radius: Float,
        drawContent: (Canvas) -> Unit
    ): Boolean {
        return runCatching {
            val width = max(1, ceil(area.width()).toInt())
            val height = max(1, ceil(area.height()).toInt())
            val node = renderNode ?: RenderNode("FrostedGlassBlur").also {
                renderNode = it
            }
            node.setPosition(0, 0, width, height)
            node.setRenderEffect(
                RenderEffect.createBlurEffect(
                    radius,
                    radius,
                    Shader.TileMode.CLAMP
                )
            )
            val recordingCanvas = node.beginRecording(width, height)
            drawContent(recordingCanvas)
            node.endRecording()
            val saveCount = canvas.save()
            canvas.translate(area.left, area.top)
            canvas.drawRenderNode(node)
            canvas.restoreToCount(saveCount)
            true
        }.getOrDefault(false)
    }
}

internal fun createNativeFrostedGlassBlur(): FrostedGlassBlur? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        NativeFrostedGlassBlur()
    } else {
        null
    }
}

internal class FrostedGlassFrameLayout @JvmOverloads constructor(
    context: android.content.Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private val nativeBlur = createNativeFrostedGlassBlur()
    private val legacyBlur = LegacyFrostedGlassBlur(
        FrostedGlassStyle.TOOLBAR_LEGACY_DOWNSAMPLE,
        FrostedGlassStyle.TOOLBAR_LEGACY_BLUR_ROUNDS
    )
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grainPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private var backdropSource: View? = null
    private var grainBitmap: Bitmap? = null
    private var grainShader: BitmapShader? = null

    var blurRadius: Float = FrostedGlassStyle.toolbarBlurRadiusPx(resources.displayMetrics.density)
        set(value) {
            field = value
            invalidate()
        }

    init {
        setWillNotDraw(false)
    }

    fun setBackdropSource(source: View) {
        backdropSource = source
        invalidate()
    }

    override fun draw(canvas: Canvas) {
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        val source = backdropSource
        if (source != null) {
            val blurred = (nativeBlur?.drawBlurredViewSnapshot(
                canvas,
                this,
                source,
                bounds,
                blurRadius,
                FrostedGlassStyle.TOOLBAR_REFRACTION_MAGNIFICATION
            ) == true) || legacyBlur.drawBlurredViewSnapshot(
                canvas,
                this,
                source,
                bounds,
                blurRadius,
                FrostedGlassStyle.TOOLBAR_REFRACTION_MAGNIFICATION
            )
            if (!blurred) {
                glassPaint.color = FrostedGlassStyle.darkBottom(136)
                canvas.drawRect(bounds, glassPaint)
            }
        }
        drawToolbarFrostVeil(canvas)
        canvas.drawDarkFrostedPanel(
            bounds = bounds,
            paint = glassPaint,
            topAlpha = FrostedGlassStyle.TOOLBAR_TOP_ALPHA,
            bottomAlpha = FrostedGlassStyle.TOOLBAR_BOTTOM_ALPHA,
            hairlineAlpha = FrostedGlassStyle.TOOLBAR_HAIRLINE_ALPHA,
            shadowAlpha = 0,
            topHighlightAlpha = 0,
            drawTopEdge = false
        )
        drawToolbarGlassSheen(canvas)
        drawFineGrain(canvas)
        drawToolbarGlassEdges(canvas)
        super.draw(canvas)
    }

    override fun onDetachedFromWindow() {
        legacyBlur.release()
        grainBitmap?.recycle()
        grainBitmap = null
        grainShader = null
        super.onDetachedFromWindow()
    }

    private fun drawFineGrain(canvas: Canvas) {
        grainPaint.shader = toolbarGrainShader()
        canvas.drawRect(bounds, grainPaint)
        grainPaint.shader = null
    }

    private fun drawToolbarFrostVeil(canvas: Canvas) {
        if (bounds.height() <= 0f) {
            return
        }
        glassPaint.shader = LinearGradient(
            0f,
            bounds.top,
            0f,
            bounds.bottom,
            intArrayOf(
                FrostedGlassStyle.coolHighlight(34),
                FrostedGlassStyle.highlight(18),
                FrostedGlassStyle.coolHighlight(8),
                FrostedGlassStyle.highlight(20)
            ),
            floatArrayOf(0f, 0.22f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(bounds, glassPaint)
        glassPaint.shader = null
    }

    private fun drawToolbarGlassSheen(canvas: Canvas) {
        if (bounds.height() <= 0f) {
            return
        }
        glassPaint.shader = LinearGradient(
            bounds.left,
            bounds.top,
            bounds.right,
            bounds.bottom,
            intArrayOf(
                FrostedGlassStyle.highlight(42),
                FrostedGlassStyle.coolHighlight(16),
                Color.TRANSPARENT,
                FrostedGlassStyle.shadow(12)
            ),
            floatArrayOf(0f, 0.24f, 0.68f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(bounds, glassPaint)
        glassPaint.shader = null
    }

    private fun drawToolbarGlassEdges(canvas: Canvas) {
        if (bounds.height() <= 1f) {
            return
        }
        val density = resources.displayMetrics.density
        val bottomDepth = max(4f, 1.8f * density)
        val depthTop = bounds.bottom - bottomDepth
        edgePaint.shader = LinearGradient(
            0f,
            depthTop,
            0f,
            bounds.bottom,
            intArrayOf(
                Color.TRANSPARENT,
                FrostedGlassStyle.highlight(30),
                FrostedGlassStyle.shadow(58)
            ),
            floatArrayOf(0f, 0.38f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(bounds.left, depthTop, bounds.right, bounds.bottom, edgePaint)
        edgePaint.shader = null
        edgePaint.color = FrostedGlassStyle.highlight(36)
        canvas.drawRect(bounds.left, bounds.top, bounds.right, bounds.top + 1f, edgePaint)
        edgePaint.shader = null
        edgePaint.color = FrostedGlassStyle.highlight(FrostedGlassStyle.TOOLBAR_HAIRLINE_ALPHA)
        canvas.drawRect(bounds.left, bounds.bottom - 1f, bounds.right, bounds.bottom, edgePaint)
    }

    private fun toolbarGrainShader(): BitmapShader {
        grainShader?.let { return it }
        val bitmap = createToolbarGrainBitmap()
        grainBitmap = bitmap
        return BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).also {
            grainShader = it
        }
    }

    private fun createToolbarGrainBitmap(): Bitmap {
        val size = 128
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val noise = (x * 73 + y * 151 + x * y * 17 + ((x * 29) xor (y * 61))) and 0xFF
                val bright = (noise and 1) == 0
                val alpha = if (bright) {
                    4 + noise % 10
                } else {
                    2 + noise % 6
                }
                val channel = if (bright) 255 else 0
                bitmap.setPixel(x, y, Color.argb(alpha, channel, channel, channel))
            }
        }
        return bitmap
    }
}

internal class FrostedRulerOverlayView @JvmOverloads constructor(
    context: android.content.Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val nativeBlur = createNativeFrostedGlassBlur()
    private val legacyBlur = LegacyFrostedGlassBlur()
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grainPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private val localBlurBounds = RectF()
    private val pixelCopyRect = Rect()
    private val pixelCopyHandler = Handler(Looper.getMainLooper())
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 255, 255, 255)
        strokeWidth = 1f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(WaterfallMetrics.C_TICK_ALPHA, 255, 255, 255)
        textAlign = Paint.Align.CENTER
        textSize = 10f * resources.displayMetrics.density
    }
    private val impactPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val particleCorePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var backdropSource: View? = null
    private var rulerTopProvider: (() -> Float)? = null
    private var displayStateProvider: (() -> WaterfallDisplayState)? = null
    private var scaleGuideProvider: (() -> ScaleGuide)? = null
    private var octaveDivisionsProvider: (() -> Int)? = null
    private var impactProvider: (() -> List<WaterfallRulerImpact>)? = null
    private var particleProvider: (() -> List<WaterfallRulerParticle>)? = null
    private var captureBitmap: Bitmap? = null
    private var grainBitmap: Bitmap? = null
    private var grainShader: BitmapShader? = null
    private var captureInFlight = false
    private var lastCaptureMs = 0L

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setBackdropSource(source: View) {
        backdropSource = source
        invalidate()
    }

    fun setRulerTopProvider(provider: () -> Float) {
        rulerTopProvider = provider
        invalidate()
    }

    fun setRulerContentProviders(
        displayStateProvider: () -> WaterfallDisplayState,
        scaleGuideProvider: () -> ScaleGuide,
        octaveDivisionsProvider: () -> Int
    ) {
        this.displayStateProvider = displayStateProvider
        this.scaleGuideProvider = scaleGuideProvider
        this.octaveDivisionsProvider = octaveDivisionsProvider
        invalidate()
    }

    fun setRulerImpactProvider(provider: () -> List<WaterfallRulerImpact>) {
        impactProvider = provider
        invalidate()
    }

    fun setRulerParticleProvider(provider: () -> List<WaterfallRulerParticle>) {
        particleProvider = provider
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val top = rulerTopProvider?.invoke()?.coerceIn(0f, height.toFloat()) ?: return
        if (top >= height) {
            return
        }
        bounds.set(0f, top, width.toFloat(), height.toFloat())
        val source = backdropSource
        if (source != null) {
            val blurred = if (source is SurfaceView) {
                drawBlurredSurfaceSnapshot(canvas, source, bounds)
            } else {
                (nativeBlur?.drawBlurredViewSnapshot(
                    canvas = canvas,
                    target = this,
                    sourceView = source,
                    area = bounds,
                    radius = FrostedGlassStyle.RULER_BLUR_RADIUS,
                    magnification = 1f
                ) == true) || legacyBlur.drawBlurredViewSnapshot(
                    canvas = canvas,
                    target = this,
                    sourceView = source,
                    area = bounds,
                    radius = FrostedGlassStyle.RULER_BLUR_RADIUS
                )
            }
            if (!blurred) {
                glassPaint.color = FrostedGlassStyle.darkBottom(56)
                canvas.drawRect(bounds, glassPaint)
            }
        }
        drawRulerFrostVeil(canvas, bounds)
        canvas.drawDarkFrostedPanel(
            bounds = bounds,
            paint = glassPaint,
            topAlpha = FrostedGlassStyle.RULER_TOP_ALPHA,
            bottomAlpha = FrostedGlassStyle.RULER_BOTTOM_ALPHA,
            hairlineAlpha = FrostedGlassStyle.RULER_HAIRLINE_ALPHA,
            shadowAlpha = FrostedGlassStyle.RULER_SHADOW_ALPHA,
            topHighlightAlpha = 36
        )
        drawRulerGlassSheen(canvas, bounds)
        drawRulerFineGrain(canvas, bounds)
        drawRulerGlassEdges(canvas, bounds)
        drawRulerTicks(canvas, bounds)
        val hasImpacts = drawRulerImpacts(canvas, bounds)
        val hasParticles = drawRulerParticles(canvas, bounds)
        if (hasImpacts || hasParticles) {
            postInvalidateOnAnimation()
        }
    }

    override fun onDetachedFromWindow() {
        legacyBlur.release()
        captureBitmap?.recycle()
        captureBitmap = null
        grainBitmap?.recycle()
        grainBitmap = null
        grainShader = null
        super.onDetachedFromWindow()
    }

    private fun drawRulerFrostVeil(canvas: Canvas, area: RectF) {
        if (area.height() <= 0f) {
            return
        }
        glassPaint.shader = LinearGradient(
            0f,
            area.top,
            0f,
            area.bottom,
            intArrayOf(
                FrostedGlassStyle.coolHighlight(74),
                FrostedGlassStyle.highlight(38),
                FrostedGlassStyle.coolHighlight(18),
                FrostedGlassStyle.shadow(22)
            ),
            floatArrayOf(0f, 0.2f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(area, glassPaint)
        glassPaint.shader = null
    }

    private fun drawRulerGlassSheen(canvas: Canvas, area: RectF) {
        if (area.height() <= 0f) {
            return
        }
        glassPaint.shader = LinearGradient(
            area.left,
            area.top,
            area.right,
            area.bottom,
            intArrayOf(
                FrostedGlassStyle.highlight(96),
                FrostedGlassStyle.coolHighlight(42),
                Color.TRANSPARENT,
                FrostedGlassStyle.shadow(30)
            ),
            floatArrayOf(0f, 0.18f, 0.58f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(area, glassPaint)
        glassPaint.shader = null
    }

    private fun drawRulerFineGrain(canvas: Canvas, area: RectF) {
        grainPaint.shader = rulerGrainShader()
        canvas.drawRect(area, grainPaint)
        grainPaint.shader = null
    }

    private fun drawRulerGlassEdges(canvas: Canvas, area: RectF) {
        if (area.height() <= 1f) {
            return
        }
        val density = resources.displayMetrics.density
        val topDepth = min(area.height(), max(3.5f, 1.25f * density))
        val bottomDepth = min(area.height(), max(8f, 3.0f * density))
        edgePaint.shader = LinearGradient(
            0f,
            area.top,
            0f,
            area.top + topDepth,
            intArrayOf(
                FrostedGlassStyle.highlight(118),
                FrostedGlassStyle.coolHighlight(28),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.30f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(area.left, area.top, area.right, area.top + topDepth, edgePaint)
        edgePaint.shader = null
        val depthTop = area.bottom - bottomDepth
        edgePaint.shader = LinearGradient(
            0f,
            depthTop,
            0f,
            area.bottom,
            intArrayOf(
                Color.TRANSPARENT,
                FrostedGlassStyle.highlight(34),
                FrostedGlassStyle.shadow(86)
            ),
            floatArrayOf(0f, 0.34f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(area.left, depthTop, area.right, area.bottom, edgePaint)
        edgePaint.shader = null
        edgePaint.color = FrostedGlassStyle.highlight(FrostedGlassStyle.RULER_HAIRLINE_ALPHA)
        canvas.drawRect(area.left, area.top, area.right, area.top + 1f, edgePaint)
        edgePaint.color = FrostedGlassStyle.shadow(72)
        canvas.drawRect(area.left, area.bottom - 1f, area.right, area.bottom, edgePaint)
    }

    private fun rulerGrainShader(): BitmapShader {
        grainShader?.let { return it }
        val bitmap = createRulerGrainBitmap()
        grainBitmap = bitmap
        return BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).also {
            grainShader = it
        }
    }

    private fun createRulerGrainBitmap(): Bitmap {
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

    private fun drawBlurredSurfaceSnapshot(canvas: Canvas, source: SurfaceView, area: RectF): Boolean {
        requestSurfaceSnapshot(source, area)
        val bitmap = captureBitmap ?: return false
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return false
        }
        localBlurBounds.set(0f, 0f, area.width(), area.height())
        val saveCount = canvas.save()
        canvas.translate(area.left, area.top)
        val blurred = (nativeBlur?.drawBlurredBitmapRegion(
            canvas = canvas,
            sourceBitmap = bitmap,
            area = localBlurBounds,
            radius = FrostedGlassStyle.RULER_BLUR_RADIUS
        ) == true) || legacyBlur.drawBlurredBitmapRegion(
            canvas = canvas,
            sourceBitmap = bitmap,
            area = localBlurBounds,
            radius = FrostedGlassStyle.RULER_BLUR_RADIUS
        )
        canvas.restoreToCount(saveCount)
        return blurred
    }

    private fun requestSurfaceSnapshot(source: SurfaceView, area: RectF) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || captureInFlight) {
            return
        }
        if (source.width <= 0 || source.height <= 0) {
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastCaptureMs < SURFACE_CAPTURE_INTERVAL_MS) {
            return
        }
        val captureWidth = max(1, area.width().roundToInt())
        val captureHeight = max(1, area.height().roundToInt())
        val current = captureBitmap
        if (current == null ||
            current.width != captureWidth ||
            current.height != captureHeight ||
            current.isRecycled
        ) {
            current?.recycle()
            captureBitmap = Bitmap.createBitmap(captureWidth, captureHeight, Bitmap.Config.ARGB_8888)
        }
        val target = captureBitmap ?: return
        val overlayLocation = IntArray(2)
        val sourceLocation = IntArray(2)
        getLocationInWindow(overlayLocation)
        source.getLocationInWindow(sourceLocation)
        val left = (overlayLocation[0] + area.left - sourceLocation[0]).roundToInt()
        val top = (overlayLocation[1] + area.top - sourceLocation[1]).roundToInt()
        pixelCopyRect.set(
            left.coerceIn(0, max(0, source.width - 1)),
            top.coerceIn(0, max(0, source.height - 1)),
            (left + captureWidth).coerceIn(1, source.width),
            (top + captureHeight).coerceIn(1, source.height)
        )
        if (pixelCopyRect.width() <= 0 || pixelCopyRect.height() <= 0) {
            return
        }
        captureInFlight = true
        lastCaptureMs = now
        runCatching {
            PixelCopy.request(source, pixelCopyRect, target, { result ->
                captureInFlight = false
                if (result == PixelCopy.SUCCESS) {
                    invalidate()
                }
            }, pixelCopyHandler)
        }.onFailure {
            captureInFlight = false
        }
    }

    private fun drawRulerImpacts(canvas: Canvas, area: RectF): Boolean {
        val impacts = impactProvider?.invoke().orEmpty()
        if (impacts.isEmpty()) {
            return false
        }
        val displayState = displayStateProvider?.invoke() ?: return false
        val guide = scaleGuideProvider?.invoke() ?: return false
        val edo = octaveDivisionsProvider?.invoke() ?: return false
        val layout = WaterfallLayout(
            playheadSeconds = 0.0,
            pixelsPerSecond = displayState.pixelsPerSecond,
            pitchZoomScale = displayState.pitchZoomScale,
            pitchPanSemitones = displayState.pitchPanSemitones,
            waterfallOffsetCents = displayState.waterfallOffsetCents,
            density = resources.displayMetrics.density
        )
        val keyHeight = area.height()
        impacts.forEach { impact ->
            val maxLife = impact.maxLife.takeIf { it > 0f } ?: return@forEach
            val progress = (impact.life / maxLife).coerceIn(0f, 1f)
            val velocityRatio = impact.velocityRatio.coerceIn(0f, 1f)
            val amount = sin(progress * Math.PI).toFloat() * velocityRatio
            val fade = progress.pow(0.72f)
            val tick = guide.impactTickForPitch(edo, impact.pitch, keyHeight)
            val maxAmplitude = max(0f, keyHeight - 2f - tick.length) * velocityRatio
            val tickLength = min(keyHeight - 2f, tick.length + maxAmplitude * amount)
            val yOffset = -min(4f, keyHeight * 0.08f) * amount
            val x = layout.pitchToX(impact.pitch, width.toFloat())
            if (x < -1f || x > width + 1f) {
                return@forEach
            }
            val alpha = (0.54f + fade * (0.30f + velocityRatio * 0.14f)).coerceIn(0f, 0.98f)
            impactPaint.color = hsvColor(impact.hue, 0.94f, 0.98f, alpha)
            impactPaint.strokeWidth = max(
                1.5f,
                tick.strokeWidth + WaterfallMetrics.IMPACT_EXTRA_WIDTH * (0.35f + amount)
            )
            canvas.drawLine(x, area.top + yOffset, x, area.top + yOffset + tickLength, impactPaint)
        }
        return true
    }

    private fun drawRulerParticles(canvas: Canvas, area: RectF): Boolean {
        val particles = particleProvider?.invoke().orEmpty()
        if (particles.isEmpty()) {
            return false
        }
        val usesAddBlend = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        if (usesAddBlend) {
            particlePaint.blendMode = BlendMode.PLUS
            particleCorePaint.blendMode = BlendMode.PLUS
        }
        val saveCount = canvas.save()
        canvas.clipRect(area)
        var drewParticle = false
        particles.forEach { particle ->
            val maxLife = particle.maxLife.takeIf { it > 0f } ?: return@forEach
            val x = particle.x
            val y = area.top + particle.yFromRulerTop
            if (x < -12f || x > width + 12f || y < area.top - 24f || y > area.bottom + 24f) {
                return@forEach
            }
            val lifeRatio = (particle.life / maxLife).coerceIn(0f, 1f)
            val alpha = lifeRatio.pow(1.05f)
            val size = particle.size * (1f + (1f - alpha) * 0.92f)
            val glowSize = size * 2.45f
            particlePaint.shader = null
            particlePaint.style = Paint.Style.STROKE
            particlePaint.strokeCap = Paint.Cap.ROUND
            particlePaint.strokeWidth = max(1.5f, size * 0.95f)
            particlePaint.color = hsvColor(
                particle.hue,
                0.94f,
                min(0.95f, particle.lightness + 0.15f),
                alpha * 0.68f
            )
            canvas.drawLine(
                x,
                y,
                x - particle.vx * 0.024f,
                y - particle.vy * 0.024f,
                particlePaint
            )
            particlePaint.style = Paint.Style.FILL
            particlePaint.shader = RadialGradient(
                x,
                y,
                glowSize,
                intArrayOf(
                    hsvColor(particle.hue, 0.98f, 1.0f, alpha * 0.72f),
                    hsvColor(particle.hue, 0.96f, 0.78f, alpha * 0.34f),
                    hsvColor(particle.hue, 0.96f, 0.72f, 0f)
                ),
                floatArrayOf(0f, 0.36f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(x, y, glowSize, particlePaint)
            particlePaint.shader = null
            particleCorePaint.style = Paint.Style.FILL
            particleCorePaint.color = hsvColor(particle.hue, 0.64f, 1.0f, alpha * 0.92f)
            canvas.drawCircle(x, y, max(1.2f, size * 0.54f), particleCorePaint)
            drewParticle = true
        }
        canvas.restoreToCount(saveCount)
        if (usesAddBlend) {
            particlePaint.blendMode = null
            particleCorePaint.blendMode = null
        }
        particlePaint.shader = null
        return drewParticle
    }

    private fun drawRulerTicks(canvas: Canvas, area: RectF) {
        val displayState = displayStateProvider?.invoke() ?: return
        val guide = scaleGuideProvider?.invoke() ?: return
        val edo = octaveDivisionsProvider?.invoke() ?: return
        val density = resources.displayMetrics.density
        val keyHeight = area.height()
        val layout = WaterfallLayout(
            playheadSeconds = 0.0,
            pixelsPerSecond = displayState.pixelsPerSecond,
            pitchZoomScale = displayState.pitchZoomScale,
            pitchPanSemitones = displayState.pitchPanSemitones,
            waterfallOffsetCents = displayState.waterfallOffsetCents,
            density = density
        )
        if (guide.hasScale(edo)) {
            guide.linesForVisibleRange(edo, layout.visiblePitchMin(), layout.visiblePitchMax())
                .forEach { line ->
                    val x = layout.pitchToX(line.pitch, width.toFloat())
                    if (x < -1f || x > width + 1f) {
                        return@forEach
                    }
                    val midiPitch = round(line.pitch).toInt()
                    val isC4 = midiPitch == 60 && line.isC
                    val length = keyHeight * WaterfallMetrics.C_TICK_HEIGHT_RATIO * line.ratio
                    val tickLength = if (isC4) {
                        min(length, keyHeight - WaterfallMetrics.C4_LABEL_RESERVED_HEIGHT_DP * density)
                    } else {
                        length
                    }
                    tickPaint.color = Color.argb(
                        (WaterfallMetrics.C_TICK_ALPHA * line.ratio)
                            .roundToInt()
                            .coerceIn(0, WaterfallMetrics.C_TICK_ALPHA),
                        255,
                        255,
                        255
                    )
                    tickPaint.strokeWidth = line.strokeRatio?.let { 1.4f * it } ?: if (line.isC) 1.4f else 1f
                    val alignedX = drawPixelAlignedVerticalLine(canvas, x, area.top, area.top + tickLength, tickPaint)
                    if (isC4) {
                        canvas.drawText(
                            "C4",
                            alignedX,
                            area.bottom - WaterfallMetrics.C4_LABEL_BOTTOM_PADDING_DP * density,
                            labelPaint
                        )
                    }
                }
            return
        }
        val firstHalfStep = floor(layout.visiblePitchMin() * 2.0).toInt()
        val lastHalfStep = ceil(layout.visiblePitchMax() * 2.0).toInt()
        for (halfStep in firstHalfStep..lastHalfStep) {
            val pitch = halfStep / 2.0
            val x = layout.pitchToX(pitch, width.toFloat())
            if (x < -1f || x > width + 1f) {
                continue
            }
            val tick = guide.tickForPitch(edo, pitch, keyHeight)
            if (!tick.isVisible) {
                continue
            }
            val isC4 = tick.midiPitch == 60 && tick.isC
            val tickLength = if (isC4) {
                min(tick.length, keyHeight - WaterfallMetrics.C4_LABEL_RESERVED_HEIGHT_DP * density)
            } else {
                tick.length
            }
            tickPaint.color = Color.argb(tick.alpha, 255, 255, 255)
            tickPaint.strokeWidth = tick.strokeWidth
            val alignedX = drawPixelAlignedVerticalLine(canvas, x, area.top, area.top + tickLength, tickPaint)
            if (isC4) {
                canvas.drawText(
                    "C4",
                    alignedX,
                    area.bottom - WaterfallMetrics.C4_LABEL_BOTTOM_PADDING_DP * density,
                    labelPaint
                )
            }
        }
    }

    private fun drawPixelAlignedVerticalLine(
        canvas: Canvas,
        x: Float,
        top: Float,
        bottom: Float,
        paint: Paint
    ): Float {
        val alignedX = floor(x) + 0.5f
        canvas.drawLine(alignedX, top, alignedX, bottom, paint)
        return alignedX
    }

    private fun hsvColor(hue: Float, saturation: Float, value: Float, alpha: Float): Int {
        return Color.HSVToColor(
            (alpha * 255).roundToInt().coerceIn(0, 255),
            floatArrayOf(hue, saturation, value)
        )
    }

    private companion object {
        private const val SURFACE_CAPTURE_INTERVAL_MS = 66L
    }
}

internal fun Canvas.drawDarkFrostedPanel(
    bounds: RectF,
    paint: Paint,
    topAlpha: Int,
    bottomAlpha: Int,
    hairlineAlpha: Int,
    shadowAlpha: Int,
    topHighlightAlpha: Int = 18,
    drawTopEdge: Boolean = true
) {
    paint.shader = LinearGradient(
        0f,
        bounds.top,
        0f,
        bounds.bottom,
        intArrayOf(
            FrostedGlassStyle.highlight(topHighlightAlpha),
            FrostedGlassStyle.darkTop(topAlpha),
            FrostedGlassStyle.darkBottom(bottomAlpha)
        ),
        floatArrayOf(0f, 0.42f, 1f),
        Shader.TileMode.CLAMP
    )
    drawRect(bounds, paint)
    paint.shader = null
    if (drawTopEdge) {
        paint.color = FrostedGlassStyle.highlight(hairlineAlpha)
        drawRect(bounds.left, bounds.top, bounds.right, bounds.top + 1f, paint)
        paint.color = FrostedGlassStyle.shadow(shadowAlpha)
        drawRect(bounds.left, bounds.top - 2f, bounds.right, bounds.top, paint)
    }
}
