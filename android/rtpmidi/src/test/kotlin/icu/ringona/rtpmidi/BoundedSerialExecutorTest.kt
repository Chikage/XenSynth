package icu.ringona.rtpmidi

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedSerialExecutorTest {
    @Test
    fun executesAcceptedWorkInOrder() {
        val values = Collections.synchronizedList(mutableListOf<Int>())
        val finished = CountDownLatch(3)
        BoundedSerialExecutor(4, "bounded-serial-test", {}).use { executor ->
            repeat(3) { value ->
                assertTrue(executor.execute {
                    values += value
                    finished.countDown()
                })
            }
            assertTrue(finished.await(2, TimeUnit.SECONDS))
        }

        assertEquals(listOf(0, 1, 2), values)
    }

    @Test
    fun criticalOverloadReplacesBacklogWithRecoveryAction() {
        val running = CountDownLatch(1)
        val releaseWriter = CountDownLatch(1)
        val fallbackFinished = CountDownLatch(1)
        val discarded = Collections.synchronizedList(mutableListOf<Int>())
        val discardCallbacks = Collections.synchronizedList(mutableListOf<Int>())
        BoundedSerialExecutor(
            capacity = 2,
            threadName = "bounded-overload-test",
            criticalOverloadFallback = fallbackFinished::countDown,
        ).use { executor ->
            assertTrue(executor.execute {
                running.countDown()
                releaseWriter.await(2, TimeUnit.SECONDS)
            })
            assertTrue(running.await(2, TimeUnit.SECONDS))
            assertTrue(executor.execute(onDiscard = { discardCallbacks += 1 }) { discarded += 1 })
            assertTrue(executor.execute(onDiscard = { discardCallbacks += 2 }) { discarded += 2 })
            assertFalse(executor.execute(onDiscard = { discardCallbacks += 3 }) { discarded += 3 })

            assertFalse(
                executor.execute(
                    critical = true,
                    onDiscard = { discardCallbacks += 4 },
                ) { discarded += 4 },
            )
            releaseWriter.countDown()
            assertTrue(fallbackFinished.await(2, TimeUnit.SECONDS))

            val statistics = executor.statistics
            assertEquals(4L, statistics.droppedTasks)
            assertEquals(1L, statistics.overloadFallbacks)
        }
        assertTrue(discarded.isEmpty())
        assertEquals(setOf(1, 2, 3, 4), discardCallbacks.toSet())
    }
}
