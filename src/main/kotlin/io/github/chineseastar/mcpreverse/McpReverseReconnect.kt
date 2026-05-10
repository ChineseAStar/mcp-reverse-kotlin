package io.github.chineseastar.mcpreverse

import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages reconnection attempts with exponential backoff and optional jitter.
 */
class ReconnectManager(
    private val options: ReconnectOptions,
    private val logger: ReverseLogger = NoopLogger,
) {
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "mcp-reverse-reconnect").apply { isDaemon = true }
    }

    private val attempts = AtomicInteger(0)
    private val random = SecureRandom()

    @Volatile
    private var cancelled = false

    /**
     * Schedule a reconnect attempt. If it fails, the [attempt] callback can call
     * [scheduleNext] to schedule the next retry.
     */
    fun scheduleNext(attempt: (attemptNumber: Int) -> Unit) {
        if (cancelled) return
        if (options.maxAttempts > 0 && attempts.get() >= options.maxAttempts) {
            logger.warn("ReconnectManager: max attempts ({}) reached, giving up", options.maxAttempts)
            return
        }

        val delay = computeDelay()
        val attemptNumber = attempts.incrementAndGet()
        logger.info("ReconnectManager: scheduling attempt {} in {} ms", attemptNumber, delay.toMillis())

        scheduler.schedule(
            { attempt(attemptNumber) },
            delay.toMillis(),
            TimeUnit.MILLISECONDS
        )
    }

    /** Call when a connection succeeds to reset the backoff counter. */
    fun reset() {
        attempts.set(0)
    }

    /** Stop all pending reconnection attempts. */
    fun cancel() {
        cancelled = true
        scheduler.shutdownNow()
    }

    val isCancelled: Boolean get() = cancelled

    private fun computeDelay(): Duration {
        val multiplier = Math.pow(options.backoffMultiplier, (attempts.get()).toDouble())
        val baseMs = (options.baseDelay.toMillis() * multiplier).toLong()
        val cappedMs = minOf(baseMs, options.maxDelay.toMillis())

        if (options.jitter && cappedMs > 0) {
            val jitterMs = (cappedMs * 0.5 * random.nextDouble()).toLong()
            return Duration.ofMillis(cappedMs - jitterMs)
        }

        return Duration.ofMillis(cappedMs)
    }
}
