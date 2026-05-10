package io.github.chineseastar.mcpreverse

import java.time.Duration

/**
 * Options for the reverse SSE transport.
 */
data class ReverseSseOptions(
    /** Base URL of the public acceptor, e.g. "http://chat-ai:8080/mcp-reverse" */
    val acceptorUrl: String,

    /** A name that identifies this server instance to the acceptor */
    val serverName: String = "mcp-server",

    /** Optional bearer token sent as Authorization header */
    val authToken: String? = null,

    /** SSE connection timeout */
    val connectionTimeout: Duration = Duration.ofSeconds(30),

    /** Reconnection configuration */
    val reconnect: ReconnectOptions = ReconnectOptions(),
)

/**
 * Reconnection configuration.
 */
data class ReconnectOptions(
    /** Enable automatic reconnection */
    val enabled: Boolean = true,

    /** Maximum number of reconnect attempts (0 = unlimited) */
    val maxAttempts: Int = 0,

    /** Base delay between attempts */
    val baseDelay: Duration = Duration.ofSeconds(1),

    /** Maximum delay between attempts */
    val maxDelay: Duration = Duration.ofSeconds(30),

    /** Multiplication factor for exponential backoff */
    val backoffMultiplier: Double = 2.0,

    /** Whether to add jitter to prevent thundering herd */
    val jitter: Boolean = true,
)

/**
 * Logging interface for mcp-reverse-kotlin. Implement with SLF4J or any
 * other logging framework. The library takes no direct dependency on a
 * concrete logging implementation.
 */
interface ReverseLogger {
    fun info(msg: String, vararg args: Any?)
    fun warn(msg: String, vararg args: Any?)
    fun error(msg: String, vararg args: Any?)
    fun debug(msg: String, vararg args: Any?)
}

/**
 * No-op logger used when none is provided.
 */
object NoopLogger : ReverseLogger {
    override fun info(msg: String, vararg args: Any?) {}
    override fun warn(msg: String, vararg args: Any?) {}
    override fun error(msg: String, vararg args: Any?) {}
    override fun debug(msg: String, vararg args: Any?) {}
}
