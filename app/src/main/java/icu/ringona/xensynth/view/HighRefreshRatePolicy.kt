package icu.ringona.xensynth.view

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.SurfaceControl
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.roundToInt

object HighRefreshRatePolicy {
    const val DEFAULT_REFRESH_RATE = 60f
    const val MIN_REFRESH_RATE = 30f
    const val MAX_REFRESH_RATE = 1000f
    const val FRAME_RATE_EPSILON = 0.5f

    data class SurfaceFrameRateRequest(
        val targetFrameRate: Float,
        val appliedFrameRate: Float?
    )

    @Suppress("DEPRECATION")
    fun displayFor(view: View): Display? {
        view.display?.let { return it }
        val windowManager = view.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        return windowManager?.defaultDisplay
    }

    fun bestDisplayMode(display: Display?): Display.Mode? {
        if (display == null) {
            return null
        }
        val currentMode = display.mode
        val currentWidth = currentMode?.physicalWidth ?: 0
        val currentHeight = currentMode?.physicalHeight ?: 0
        return display.supportedModes
            ?.asSequence()
            ?.filter { mode -> mode.matchesSize(currentWidth, currentHeight) }
            ?.maxByOrNull { mode ->
                mode.supportedRefreshRates().maxOrNull() ?: DEFAULT_REFRESH_RATE
            }
    }

    fun maxSupportedRefreshRate(display: Display?): Float {
        return display
            ?.let { supportedRefreshRates(it).maxOrNull() }
            ?: DEFAULT_REFRESH_RATE
    }

    fun preferredSupportedRefreshRate(display: Display?): Float {
        return maxSupportedRefreshRate(display)
    }

    fun supportedRefreshSummary(display: Display?): String {
        if (display == null) {
            return "unknown"
        }
        val rates = linkedSetOf(sanitizeRefreshRate(display.refreshRate))
        val currentMode = display.mode
        val currentWidth = currentMode?.physicalWidth ?: 0
        val currentHeight = currentMode?.physicalHeight ?: 0
        display.supportedModes?.forEach { mode ->
            if (mode.matchesSize(currentWidth, currentHeight)) {
                rates += mode.supportedRefreshRates()
            }
        }
        return rates.sorted().joinToString(prefix = "[", postfix = "]") { formatFrameRate(it) }
    }

    fun applySurfaceFrameRate(
        surface: Surface?,
        display: Display?,
        contentActive: Boolean,
        lastAppliedFrameRate: Float,
        force: Boolean,
        tag: String
    ): SurfaceFrameRateRequest {
        return applySurfaceFrameRate(
            surface = surface,
            frameRate = preferredSupportedRefreshRate(display),
            contentActive = contentActive,
            lastAppliedFrameRate = lastAppliedFrameRate,
            force = force,
            tag = tag
        )
    }

    fun applySurfaceFrameRate(
        surface: Surface?,
        frameRate: Float,
        contentActive: Boolean,
        lastAppliedFrameRate: Float,
        force: Boolean,
        tag: String
    ): SurfaceFrameRateRequest {
        val targetFrameRate = if (contentActive) frameRate else 0f
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return SurfaceFrameRateRequest(targetFrameRate, null)
        }
        if (!force && abs(targetFrameRate - lastAppliedFrameRate) < FRAME_RATE_EPSILON) {
            return SurfaceFrameRateRequest(targetFrameRate, null)
        }
        if (surface == null || !surface.isValid) {
            return SurfaceFrameRateRequest(targetFrameRate, null)
        }
        val compatibility = frameRateCompatibility()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surface.setFrameRate(targetFrameRate, compatibility, Surface.CHANGE_FRAME_RATE_ALWAYS)
            } else {
                surface.setFrameRate(targetFrameRate, compatibility)
            }
            return SurfaceFrameRateRequest(targetFrameRate, targetFrameRate)
        } catch (error: IllegalArgumentException) {
            Log.w(tag, "Surface rejected preferred frame rate=${formatFrameRate(targetFrameRate)}", error)
        } catch (error: IllegalStateException) {
            Log.w(tag, "Surface frame-rate request failed rate=${formatFrameRate(targetFrameRate)}", error)
        }
        return SurfaceFrameRateRequest(targetFrameRate, null)
    }

    fun applySurfaceControlFrameRate(
        surfaceView: SurfaceView?,
        frameRate: Float,
        contentActive: Boolean,
        force: Boolean,
        lastAppliedFrameRate: Float,
        tag: String
    ): SurfaceFrameRateRequest {
        val targetFrameRate = if (contentActive) frameRate else 0f
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return SurfaceFrameRateRequest(targetFrameRate, null)
        }
        if (!force && abs(targetFrameRate - lastAppliedFrameRate) < FRAME_RATE_EPSILON) {
            return SurfaceFrameRateRequest(targetFrameRate, null)
        }
        val surfaceControl = surfaceView?.surfaceControl
        if (surfaceControl == null || !surfaceControl.isValid) {
            return SurfaceFrameRateRequest(targetFrameRate, null)
        }
        val compatibility = frameRateCompatibility()
        try {
            val transaction = SurfaceControl.Transaction()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                transaction.setFrameRate(
                    surfaceControl,
                    targetFrameRate,
                    compatibility,
                    Surface.CHANGE_FRAME_RATE_ALWAYS
                )
            } else {
                transaction.setFrameRate(surfaceControl, targetFrameRate, compatibility)
            }
            transaction.apply()
            return SurfaceFrameRateRequest(targetFrameRate, targetFrameRate)
        } catch (error: IllegalArgumentException) {
            Log.w(tag, "SurfaceControl rejected preferred frame rate=${formatFrameRate(targetFrameRate)}", error)
        } catch (error: IllegalStateException) {
            Log.w(tag, "SurfaceControl frame-rate request failed rate=${formatFrameRate(targetFrameRate)}", error)
        }
        return SurfaceFrameRateRequest(targetFrameRate, null)
    }

    fun formatFrameRate(value: Float): String {
        return if (abs(value - value.roundToInt()) < 0.05f) {
            value.roundToInt().toString()
        } else {
            String.format(java.util.Locale.US, "%.1f", value)
        }
    }

    private fun frameRateCompatibility(): Int = Surface.FRAME_RATE_COMPATIBILITY_DEFAULT

    private fun Display.Mode.matchesSize(currentWidth: Int, currentHeight: Int): Boolean {
        return currentWidth <= 0 || currentHeight <= 0 ||
            (physicalWidth == currentWidth && physicalHeight == currentHeight)
    }

    private fun Display.Mode.supportedRefreshRates(): List<Float> {
        val rates = mutableListOf(sanitizeRefreshRate(refreshRate))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alternativeRefreshRates.forEach { alternative ->
                rates += sanitizeRefreshRate(alternative)
            }
        }
        return rates
    }

    private fun supportedRefreshRates(display: Display): List<Float> {
        val rates = linkedSetOf(sanitizeRefreshRate(display.refreshRate))
        val currentMode = display.mode
        val currentWidth = currentMode?.physicalWidth ?: 0
        val currentHeight = currentMode?.physicalHeight ?: 0
        display.supportedModes?.forEach { mode ->
            if (mode.matchesSize(currentWidth, currentHeight)) {
                rates += mode.supportedRefreshRates()
            }
        }
        return rates
            .map { rate -> rate.coerceIn(MIN_REFRESH_RATE, MAX_REFRESH_RATE) }
            .distinct()
    }

    private fun sanitizeRefreshRate(refreshRate: Float): Float {
        return if (refreshRate.isFinite() &&
            refreshRate in MIN_REFRESH_RATE..MAX_REFRESH_RATE
        ) {
            refreshRate
        } else {
            DEFAULT_REFRESH_RATE
        }
    }
}
