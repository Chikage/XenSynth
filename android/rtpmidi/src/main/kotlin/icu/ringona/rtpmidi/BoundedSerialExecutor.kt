package icu.ringona.rtpmidi

import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal data class BoundedSerialExecutorStatistics(
    val queuedTasks: Int,
    val peakQueuedTasks: Int,
    val droppedTasks: Long,
    val overloadFallbacks: Long,
)

/**
 * A bounded one-thread executor for ordered UDP writes.
 *
 * Normal work is discarded when the socket writer cannot keep up. Critical work replaces the
 * pending backlog with one caller-provided recovery action, allowing a full MIDI panic to release
 * notes after the writer becomes available again.
 */
internal class BoundedSerialExecutor(
    capacity: Int,
    threadName: String,
    private val criticalOverloadFallback: () -> Unit,
) : Closeable {
    private class Work(
        private val action: () -> Unit,
        private val onDiscard: () -> Unit,
    ) : Runnable {
        override fun run() = action()

        fun discard() = onDiscard()
    }

    private val lock = Any()
    private val queue = ArrayBlockingQueue<Runnable>(capacity)
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        queue,
        { runnable -> Thread(runnable, threadName).apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private var closed = false
    private var peakQueuedTasks = 0
    private var droppedTasks = 0L
    private var overloadFallbacks = 0L

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    fun execute(
        critical: Boolean = false,
        onDiscard: () -> Unit = {},
        action: () -> Unit,
    ): Boolean = synchronized(lock) {
        if (closed) {
            onDiscard()
            return false
        }
        val work = Work(action, onDiscard)
        try {
            executor.execute(work)
            peakQueuedTasks = maxOf(peakQueuedTasks, queue.size)
            true
        } catch (_: RejectedExecutionException) {
            if (!critical) {
                droppedTasks++
                work.discard()
                return false
            }

            val cleared = ArrayList<Runnable>(queue.size)
            queue.drainTo(cleared)
            cleared.filterIsInstance<Work>().forEach(Work::discard)
            work.discard()
            droppedTasks += cleared.size + 1L
            overloadFallbacks++
            val accepted = queue.offer(Work(criticalOverloadFallback, {}))
            if (accepted) peakQueuedTasks = maxOf(peakQueuedTasks, queue.size)
            false
        }
    }

    val statistics: BoundedSerialExecutorStatistics
        get() = synchronized(lock) {
            BoundedSerialExecutorStatistics(
                queuedTasks = queue.size,
                peakQueuedTasks = peakQueuedTasks,
                droppedTasks = droppedTasks,
                overloadFallbacks = overloadFallbacks,
            )
        }

    override fun close() {
        val abandoned = synchronized(lock) {
            if (closed) return
            closed = true
            executor.shutdownNow()
        }
        abandoned.filterIsInstance<Work>().forEach(Work::discard)
        synchronized(lock) { droppedTasks += abandoned.size }
    }
}
