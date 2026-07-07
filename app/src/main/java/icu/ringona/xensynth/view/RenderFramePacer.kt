package icu.ringona.xensynth.view

import android.os.Build
import android.util.Log
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs
import kotlin.math.min

/**
 * Shared frame pacing and frame-rate hint helper for manual render loops.
 */
@Suppress("DEPRECATION")
class RenderFramePacer(private val view: View) {
    private var cachedFrameIntervalNanos = frameIntervalNanos(HighRefreshRatePolicy.DEFAULT_REFRESH_RATE)
    private var lastRefreshRateResolveNanos = 0L
    private var nextFrameNanos = 0L
    private var currentFrameTimeNanos = 0L
    private var lastEffectiveActive = false
    private var cachedTargetFrameRate = HighRefreshRatePolicy.DEFAULT_REFRESH_RATE
    private var cachedDisplayFrameRate = HighRefreshRatePolicy.DEFAULT_REFRESH_RATE
    private var lastAppliedSurfaceFrameRate = -1f

    val frameTimeNanos: Long
        get() = currentFrameTimeNanos.takeIf { it > 0L } ?: System.nanoTime()

    val targetFrameRate: Float
        get() = cachedTargetFrameRate

    fun reset() {
        nextFrameNanos = 0L
        currentFrameTimeNanos = 0L
        lastRefreshRateResolveNanos = 0L
        lastEffectiveActive = false
        lastAppliedSurfaceFrameRate = -1f
    }

    fun shouldRenderFrame(contentActive: Boolean, frameTimeNanosHint: Long = System.nanoTime()): Boolean {
        val now = frameTimeNanosHint.takeIf { it > 0L } ?: System.nanoTime()
        if (contentActive) {
            recordContentActive(now)
        }
        val effectiveActive = contentActive || hasRecentInteraction(now)
        val frameIntervalNanos = resolveFrameIntervalNanos(now)
        if (nextFrameNanos == 0L || (!lastEffectiveActive && effectiveActive)) {
            currentFrameTimeNanos = now
            nextFrameNanos = now + frameIntervalNanos
            lastEffectiveActive = effectiveActive
            return true
        }
        lastEffectiveActive = effectiveActive
        if (now < nextFrameNanos) {
            return false
        }
        if (now - nextFrameNanos > frameIntervalNanos * 2L) {
            currentFrameTimeNanos = now
            nextFrameNanos = now + frameIntervalNanos
        } else {
            currentFrameTimeNanos = nextFrameNanos
            do {
                nextFrameNanos += frameIntervalNanos
            } while (nextFrameNanos <= now)
        }
        return true
    }

    fun estimateSleepNanos(
        maxSleepMillis: Long,
        preferFramePrecision: Boolean,
        now: Long = System.nanoTime()
    ): Long {
        if (maxSleepMillis <= 0L) {
            return 0L
        }
        var sleepNanos = TimeUnit.MILLISECONDS.toNanos(maxSleepMillis)
        if (nextFrameNanos > now) {
            var frameSleepNanos = nextFrameNanos - now
            if (preferFramePrecision) {
                frameSleepNanos = if (frameSleepNanos > ACTIVE_FRAME_WAKEUP_MARGIN_NANOS) {
                    frameSleepNanos - ACTIVE_FRAME_WAKEUP_MARGIN_NANOS
                } else {
                    min(frameSleepNanos, ACTIVE_FRAME_FINE_SLEEP_NANOS)
                }
            }
            sleepNanos = min(sleepNanos, frameSleepNanos)
        } else if (nextFrameNanos > 0L) {
            sleepNanos = 0L
        }
        return sleepNanos.coerceAtLeast(0L)
    }

    fun sleepUntilNextTick(maxSleepMillis: Long, preferFramePrecision: Boolean = false) {
        val sleepNanos = estimateSleepNanos(maxSleepMillis, preferFramePrecision)
        if (sleepNanos > 0L) {
            LockSupport.parkNanos(sleepNanos)
        }
    }

    fun applyPreferredFrameRate(
        holder: SurfaceHolder?,
        contentActive: Boolean,
        surfaceFrameRateEnabled: Boolean = true,
        force: Boolean = false,
        tag: String
    ): Float {
        val now = System.nanoTime()
        if (contentActive) {
            recordContentActive(now)
        }
        val effectiveActive = contentActive || hasRecentInteraction(now) || hasRecentContentActive(now)
        resolveFrameIntervalNanos(now)
        val surfaceFrameRate = if (surfaceFrameRateEnabled) cachedDisplayFrameRate else 0f
        val request = HighRefreshRatePolicy.applySurfaceFrameRate(
            surface = holder?.surface,
            frameRate = surfaceFrameRate,
            contentActive = effectiveActive && surfaceFrameRateEnabled,
            lastAppliedFrameRate = lastAppliedSurfaceFrameRate,
            force = force || !surfaceFrameRateEnabled,
            tag = tag
        )
        request.appliedFrameRate?.let { lastAppliedSurfaceFrameRate = it }
        return cachedTargetFrameRate
    }

    private fun resolveFrameIntervalNanos(now: Long): Long {
        if (lastRefreshRateResolveNanos == 0L ||
            now - lastRefreshRateResolveNanos >= REFRESH_RATE_CACHE_NANOS
        ) {
            cachedDisplayFrameRate = resolveDisplayFrameRate(view)
            cachedTargetFrameRate = cachedDisplayFrameRate
            cachedFrameIntervalNanos = frameIntervalNanos(cachedTargetFrameRate)
            lastRefreshRateResolveNanos = now
        }
        return cachedFrameIntervalNanos
    }

    interface InteractionListener {
        fun onRenderFramePacerInteraction()
    }

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private val REFRESH_RATE_CACHE_NANOS = TimeUnit.SECONDS.toNanos(1)
        private val ACTIVE_FRAME_WAKEUP_MARGIN_NANOS = TimeUnit.MILLISECONDS.toNanos(1)
        private val ACTIVE_FRAME_FINE_SLEEP_NANOS = TimeUnit.MICROSECONDS.toNanos(250)
        private val RECENT_INTERACTION_NANOS = TimeUnit.MILLISECONDS.toNanos(800)
        private val interactionListeners = CopyOnWriteArrayList<WeakReference<InteractionListener>>()
        private val requestedFrameRates = WeakHashMap<View, Float>()
        @Volatile private var lastInteractionNanos = 0L
        @Volatile private var lastContentActiveNanos = 0L

        fun notifyInteraction() {
            lastInteractionNanos = System.nanoTime()
            notifyInteractionListeners()
        }

        fun notifyContentActive() {
            recordContentActive(System.nanoTime())
        }

        fun addInteractionListener(listener: InteractionListener?) {
            if (listener == null) {
                return
            }
            removeInteractionListener(listener)
            interactionListeners.add(WeakReference(listener))
        }

        fun removeInteractionListener(listener: InteractionListener?) {
            if (listener == null) {
                return
            }
            interactionListeners.forEach { reference ->
                val existing = reference.get()
                if (existing == null || existing == listener) {
                    interactionListeners.remove(reference)
                }
            }
        }

        fun hasActiveFrameDemand(): Boolean {
            val now = System.nanoTime()
            return hasRecentInteraction(now) || hasRecentContentActive(now)
        }

        fun applyWindowPreferredFrameRate(
            window: Window?,
            displayView: View?,
            contentActive: Boolean,
            force: Boolean = false,
            applyWindowRefreshRate: Boolean = true,
            applyViewRequestedFrameRate: Boolean = true,
            tag: String
        ): Float {
            if (window == null || displayView == null) {
                return HighRefreshRatePolicy.DEFAULT_REFRESH_RATE
            }
            val now = System.nanoTime()
            if (contentActive) {
                recordContentActive(now)
            }
            val frameRate = resolveDisplayFrameRate(displayView)
            applyRequestedFrameRate(
                displayView,
                if (applyViewRequestedFrameRate) frameRate else 0f,
                force = force || !applyViewRequestedFrameRate
            )
            val params = window.attributes
            var changed = false
            if (applyWindowRefreshRate) {
                changed = force ||
                    abs(params.preferredRefreshRate - frameRate) >= HighRefreshRatePolicy.FRAME_RATE_EPSILON
                params.preferredRefreshRate = frameRate
            } else if (params.preferredRefreshRate != 0f) {
                params.preferredRefreshRate = 0f
                changed = true
            }
            if (if (applyWindowRefreshRate) {
                    applyWindowFrameRatePolicy(params, frameRate)
                } else {
                    resetWindowFrameRatePolicy(params)
                }
            ) {
                changed = true
            }
            if (changed) {
                window.attributes = params
                if (applyWindowRefreshRate) {
                    Log.i(
                        tag,
                        "Requested window preferred refresh " +
                            "${HighRefreshRatePolicy.formatFrameRate(frameRate)}Hz"
                    )
                } else {
                    Log.i(tag, "Cleared window preferred refresh policy")
                }
            }
            return frameRate
        }

        fun resetWindowFrameRatePolicy(window: Window?) {
            if (window == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                return
            }
            val params = window.attributes
            if (resetWindowFrameRatePolicy(params)) {
                window.attributes = params
            }
        }

        fun applyRequestedFrameRate(view: View?, frameRate: Float, force: Boolean = false) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM || view == null) {
                return
            }
            val requestedFrameRate = requestedViewFrameRate(frameRate)
            if (!force) {
                synchronized(requestedFrameRates) {
                    val previous = requestedFrameRates[view]
                    if (previous != null &&
                        frameRatesEqual(previous, requestedFrameRate)
                    ) {
                        return
                    }
                }
            }
            try {
                view.setRequestedFrameRate(requestedFrameRate)
                cacheRequestedFrameRate(view, requestedFrameRate)
            } catch (_: IllegalArgumentException) {
            } catch (_: IllegalStateException) {
            }
        }

        fun applyRequestedFrameRateTree(view: View?, frameRate: Float, force: Boolean = false) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA || view !is ViewGroup) {
                applyRequestedFrameRate(view, frameRate, force = force)
                return
            }
            val requestedFrameRate = requestedViewFrameRate(frameRate)
            if (!force && hasRequestedFrameRate(view, requestedFrameRate)) {
                return
            }
            try {
                view.propagateRequestedFrameRate(requestedFrameRate, true)
                cacheRequestedFrameRate(view, requestedFrameRate)
            } catch (_: IllegalArgumentException) {
            } catch (_: IllegalStateException) {
            }
        }

        private fun requestedViewFrameRate(frameRate: Float): Float {
            return if (frameRate.isFinite() && frameRate > 0f) {
                frameRate
            } else {
                View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
            }
        }

        private fun hasRequestedFrameRate(view: View, requestedFrameRate: Float): Boolean {
            synchronized(requestedFrameRates) {
                val previous = requestedFrameRates[view] ?: return false
                return frameRatesEqual(previous, requestedFrameRate)
            }
        }

        private fun cacheRequestedFrameRate(view: View, requestedFrameRate: Float) {
            synchronized(requestedFrameRates) {
                requestedFrameRates[view] = requestedFrameRate
            }
        }

        private fun frameRatesEqual(left: Float, right: Float): Boolean {
            return if (left.isNaN() && right.isNaN()) {
                true
            } else {
                abs(left - right) < HighRefreshRatePolicy.FRAME_RATE_EPSILON
            }
        }

        private fun applyWindowFrameRatePolicy(
            params: WindowManager.LayoutParams,
            frameRate: Float
        ): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                return false
            }
            val highRefreshThreshold =
                HighRefreshRatePolicy.DEFAULT_REFRESH_RATE + HighRefreshRatePolicy.FRAME_RATE_EPSILON
            val holdHighRefresh = frameRate > highRefreshThreshold
            val boostOnTouch = !holdHighRefresh
            var changed = false
            if (params.getFrameRateBoostOnTouchEnabled() != boostOnTouch) {
                params.setFrameRateBoostOnTouchEnabled(boostOnTouch)
                changed = true
            }
            if (params.isFrameRatePowerSavingsBalanced()) {
                params.setFrameRatePowerSavingsBalanced(false)
                changed = true
            }
            return changed
        }

        private fun resetWindowFrameRatePolicy(params: WindowManager.LayoutParams): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                return false
            }
            var changed = false
            if (!params.getFrameRateBoostOnTouchEnabled()) {
                params.setFrameRateBoostOnTouchEnabled(true)
                changed = true
            }
            return changed
        }

        private fun resolveDisplayFrameRate(view: View?): Float {
            val display = view?.let { HighRefreshRatePolicy.displayFor(it) }
            return HighRefreshRatePolicy.preferredSupportedRefreshRate(display)
        }

        private fun frameIntervalNanos(refreshRate: Float): Long {
            val safeRate = refreshRate.coerceIn(
                HighRefreshRatePolicy.MIN_REFRESH_RATE,
                HighRefreshRatePolicy.MAX_REFRESH_RATE
            )
            return (NANOS_PER_SECOND / safeRate).toLong().coerceAtLeast(1L)
        }

        private fun hasRecentInteraction(now: Long): Boolean {
            val lastInteraction = lastInteractionNanos
            return lastInteraction > 0L && now - lastInteraction <= RECENT_INTERACTION_NANOS
        }

        private fun hasRecentContentActive(now: Long): Boolean {
            val lastContentActive = lastContentActiveNanos
            return lastContentActive > 0L && now - lastContentActive <= RECENT_INTERACTION_NANOS
        }

        private fun recordContentActive(now: Long) {
            val wasActive = hasRecentContentActive(now)
            lastContentActiveNanos = now
            if (!wasActive) {
                notifyInteractionListeners()
            }
        }

        private fun notifyInteractionListeners() {
            interactionListeners.forEach { reference ->
                val listener = reference.get()
                if (listener == null) {
                    interactionListeners.remove(reference)
                } else {
                    listener.onRenderFramePacerInteraction()
                }
            }
        }
    }
}
