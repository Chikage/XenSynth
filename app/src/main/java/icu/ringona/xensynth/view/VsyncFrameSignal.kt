package icu.ringona.xensynth.view

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import java.util.concurrent.locks.LockSupport

/**
 * Relays main-thread Choreographer frame timestamps to a render thread.
 */
internal class VsyncFrameSignal : Choreographer.FrameCallback {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile private var renderThread: Thread? = null
    @Volatile private var latestFrameTimeNanos = 0L

    fun start(renderThread: Thread) {
        this.renderThread = renderThread
        latestFrameTimeNanos = 0L
        running = true
        runOnMain {
            if (running) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    fun stop() {
        running = false
        renderThread?.let { LockSupport.unpark(it) }
        renderThread = null
        runOnMain {
            Choreographer.getInstance().removeFrameCallback(this)
        }
    }

    fun latestFrameTimeNanos(): Long = latestFrameTimeNanos

    fun waitForNextFrame(consumedFrameTimeNanos: Long, timeoutNanos: Long): Long {
        if (timeoutNanos <= 0L) {
            return latestFrameTimeNanos
        }
        val deadlineNanos = System.nanoTime() + timeoutNanos
        while (running && latestFrameTimeNanos == consumedFrameTimeNanos) {
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L || Thread.currentThread().isInterrupted) {
                break
            }
            LockSupport.parkNanos(this, remainingNanos)
        }
        return latestFrameTimeNanos
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) {
            return
        }
        latestFrameTimeNanos = frameTimeNanos
        renderThread?.let { LockSupport.unpark(it) }
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }
}
