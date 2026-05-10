package io.github.chineseastar.mcpreverse

import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpReverseReconnectTest {

    @Test
    fun `scheduleNext fires callback after delay`() {
        val options = ReconnectOptions(
            enabled = true,
            baseDelay = Duration.ofMillis(10),
            maxDelay = Duration.ofMillis(100),
            jitter = false,
        )

        val manager = ReconnectManager(options)
        val latch = CountDownLatch(1)
        val captured = mutableListOf<Int>()

        manager.scheduleNext { n ->
            captured.add(n)
            latch.countDown()
        }

        val completed = latch.await(2, TimeUnit.SECONDS)
        assertTrue(completed, "Callback should fire within timeout")
        assertEquals(1, captured.first())
        manager.cancel()
    }

    @Test
    fun `reset clears counter so next attempt starts at 1`() {
        val options = ReconnectOptions(
            enabled = true,
            baseDelay = Duration.ofMillis(5),
            maxDelay = Duration.ofMillis(20),
            jitter = false,
        )

        val manager = ReconnectManager(options)

        // First attempt
        val latch1 = CountDownLatch(1)
        var attempt1 = -1
        manager.scheduleNext { n -> attempt1 = n; latch1.countDown() }
        latch1.await(2, TimeUnit.SECONDS)
        assertEquals(1, attempt1)

        manager.reset()

        // After reset, next should be attempt 1 again
        val latch2 = CountDownLatch(1)
        var attempt2 = -1
        manager.scheduleNext { n -> attempt2 = n; latch2.countDown() }
        latch2.await(2, TimeUnit.SECONDS)
        assertEquals(1, attempt2)

        manager.cancel()
    }

    @Test
    fun `cancel sets isCancelled flag`() {
        val options = ReconnectOptions(
            baseDelay = Duration.ofSeconds(10),
            jitter = false,
        )

        val manager = ReconnectManager(options)
        manager.cancel()
        assertTrue(manager.isCancelled)
    }

    @Test
    fun `delay increases exponentially`() {
        val options = ReconnectOptions(
            enabled = true,
            baseDelay = Duration.ofMillis(10),
            maxDelay = Duration.ofSeconds(10),
            backoffMultiplier = 2.0,
            jitter = false,
        )

        val manager = ReconnectManager(options)

        // Attempt 1: baseDelay * 2^0 = 10ms
        val latch1 = CountDownLatch(1)
        val start1 = System.currentTimeMillis()
        manager.scheduleNext { _ -> latch1.countDown() }
        latch1.await(5, TimeUnit.SECONDS)
        val delay1 = System.currentTimeMillis() - start1
        assertTrue(delay1 in 0..50, "Attempt 1 delay should be ~10ms, got ${delay1}ms")

        // Attempt 2: baseDelay * 2^1 = 20ms
        val latch2 = CountDownLatch(1)
        val start2 = System.currentTimeMillis()
        manager.scheduleNext { _ -> latch2.countDown() }
        latch2.await(5, TimeUnit.SECONDS)
        val delay2 = System.currentTimeMillis() - start2
        assertTrue(delay2 in 10..60, "Attempt 2 delay should be ~20ms, got ${delay2}ms")

        manager.cancel()
    }
}
