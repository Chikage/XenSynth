package icu.ringona.xensynth.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class WaterfallBackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) {
            return
        }
        drawWaterfallScrim(canvas, width, height)
        drawGlassMask(canvas, width, keyboardTop(height))
    }

    private fun drawWaterfallScrim(canvas: Canvas, width: Float, height: Float) {
        paint.shader = LinearGradient(
            0f,
            0f,
            0f,
            height,
            intArrayOf(
                Color.argb(WaterfallMetrics.WATERFALL_BACKGROUND_TOP_ALPHA, 23, 22, 20),
                Color.argb(WaterfallMetrics.WATERFALL_BACKGROUND_MIDDLE_ALPHA, 13, 13, 13),
                Color.argb(WaterfallMetrics.WATERFALL_BACKGROUND_BOTTOM_ALPHA, 25, 21, 17)
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width, height, paint)
        paint.shader = LinearGradient(
            0f,
            0f,
            0f,
            max(1f, height * 0.28f),
            Color.argb(WaterfallMetrics.WATERFALL_BACKGROUND_HIGHLIGHT_ALPHA, 255, 255, 255),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width, max(1f, height * 0.28f), paint)
        paint.shader = LinearGradient(
            0f,
            height * 0.54f,
            0f,
            height,
            Color.TRANSPARENT,
            Color.argb(WaterfallMetrics.WATERFALL_BACKGROUND_GLOW_ALPHA, 70, 56, 38),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, height * 0.54f, width, height, paint)
        paint.shader = null
    }

    private fun drawGlassMask(canvas: Canvas, width: Float, bottom: Float) {
        if (bottom <= 1f || width <= 1f) {
            return
        }
        paint.shader = LinearGradient(
            0f,
            0f,
            0f,
            bottom,
            Color.argb(WaterfallMetrics.WATERFALL_GLASS_TOP_ALPHA, 7, 7, 8),
            Color.argb(WaterfallMetrics.WATERFALL_GLASS_MIDDLE_ALPHA, 12, 12, 13),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width, bottom, paint)
        paint.shader = LinearGradient(
            0f,
            bottom * 0.34f,
            0f,
            bottom,
            Color.TRANSPARENT,
            Color.argb(WaterfallMetrics.WATERFALL_GLASS_BOTTOM_ALPHA, 8, 8, 8),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, bottom * 0.34f, width, bottom, paint)
        paint.shader = LinearGradient(
            0f,
            0f,
            0f,
            max(1f, bottom * 0.16f),
            Color.argb(WaterfallMetrics.WATERFALL_GLASS_GLEAM_ALPHA, 118, 114, 104),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width, max(1f, bottom * 0.16f), paint)
        paint.shader = null
    }

    private fun keyboardTop(height: Float): Float {
        val density = resources.displayMetrics.density
        val keyHeight = minOf(118f * density, maxOf(72f * density, height * 0.118f))
        return height - keyHeight
    }
}
