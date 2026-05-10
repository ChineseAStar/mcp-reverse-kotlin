package io.github.chineseastar.mcpreverse.sse

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.chineseastar.mcpreverse.NoopLogger
import io.github.chineseastar.mcpreverse.ReconnectManager
import io.github.chineseastar.mcpreverse.ReverseLogger
import io.github.chineseastar.mcpreverse.ReverseSseOptions
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpServerSession
import io.modelcontextprotocol.spec.McpServerTransportProvider
import reactor.core.publisher.Mono
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicReference

/**
 * Reverse SSE transport provider for the **internal side** (the MCP server
 * behind NAT). Implements [McpServerTransportProvider] so it can be passed
 * directly to `McpServer.sync(this)` or `McpServer.async(this)`.
 *
 * Instead of listening for incoming connections, this provider **actively
 * connects out** to a public [SseAcceptor] via an SSE long-poll. The MCP
 * protocol then runs bidirectionally over this channel.
 *
 * ### Usage:
 * ```kotlin
 * val transport = ReverseSseTransport(
 *     ReverseSseOptions(
 *         acceptorUrl = "http://chat-ai:8080/mcp-reverse",
 *         serverName = "jadx-mcp",
 *         reconnect = ReconnectOptions(enabled = true),
 *     )
 * )
 * val server = McpServer.sync(transport)
 *     .serverInfo(Implementation("jadx-mcp", "1.0"))
 *     .build()
 * ```
 */
class ReverseSseTransport(
    private val options: ReverseSseOptions,
    private val objectMapper: ObjectMapper = ObjectMapper(),
    private val logger: ReverseLogger = NoopLogger,
) : McpServerTransportProvider {

    private val httpClient: HttpClient = HttpClient.newHttpClient()
    private val reconnectManager = ReconnectManager(options.reconnect, logger)
    private val sessionFactoryRef = AtomicReference<McpServerSession.Factory>()
    private val currentSession = AtomicReference<McpServerSession>()
    private val readerThread = AtomicReference<Thread>()
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    // ── McpServerTransportProvider ─────────────────────────────────

    override fun setSessionFactory(sessionFactory: McpServerSession.Factory) {
        sessionFactoryRef.set(sessionFactory)
        startSseConnection()
    }

    override fun notifyClients(method: String, params: Any?): Mono<Void> {
        return currentSession.get()?.sendNotification(method, params) ?: Mono.empty()
    }

    override fun closeGracefully(): Mono<Void> {
        return Mono.fromRunnable {
            closed.set(true)
            reconnectManager.cancel()
            readerThread.get()?.interrupt()
            logger.info("ReverseSseTransport: closed for server '{}'", options.serverName)
        }
    }

    // ── Internal ───────────────────────────────────────────────────

    private fun startSseConnection() {
        if (closed.get()) return

        val thread = Thread(this::runSseLoop, "mcp-reverse-sse-${options.serverName}")
        thread.isDaemon = true
        readerThread.set(thread)
        thread.start()
    }

    private fun runSseLoop() {
        val factory = sessionFactoryRef.get() ?: run {
            logger.error("ReverseSseTransport: sessionFactory not set")
            return
        }

        val url = "${options.acceptorUrl}/sse?server_name=${options.serverName}"

        try {
            val requestBuilder = HttpRequest.newBuilder()
                .uri(URI(url))
                .timeout(options.connectionTimeout)
                .header("X-MCP-Server-Name", options.serverName)
                .header("Accept", "text/event-stream")
                .GET()

            options.authToken?.let { token ->
                requestBuilder.header("Authorization", "Bearer $token")
            }

            val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofLines())

            if (response.statusCode() != 200) {
                logger.error("ReverseSseTransport: SSE connect returned HTTP {}", response.statusCode())
                scheduleReconnect()
                return
            }

            // Session ID: prefer server-assigned, fall back to client-generated
            val serverSessionId = response.headers().firstValue("x-session-id").orElse(null)
            val sessionId = serverSessionId
                ?: "${options.serverName}-${System.currentTimeMillis()}-${(Math.random() * 1e7).toLong()}"
            logger.debug("ReverseSseTransport: sessionId={}", sessionId)

            // Create transport + MCP session immediately (don't wait for endpoint event)
            val messageUrl = "${options.acceptorUrl}/message?sessionId=$sessionId"
            val transport = SseServerTransport(
                messageUrl = messageUrl,
                sessionId = sessionId,
                serverName = options.serverName,
                authToken = options.authToken,
                httpClient = httpClient,
                objectMapper = objectMapper,
                logger = logger,
            )
            val session = factory.create(transport)
            currentSession.set(session)
            logger.info("ReverseSseTransport: session created for server '{}' (sessionId={})", options.serverName, sessionId)

            reconnectManager.reset()
            logger.info("ReverseSseTransport: SSE connected to {}", options.acceptorUrl)

            // Parse incoming SSE events — only 'message' events carry JSON-RPC payloads
            val parser = SseParser { event, data ->
                when (event) {
                    "message" -> onMessage(data)
                    else -> logger.debug("ReverseSseTransport: ignoring SSE event '{}'", event)
                }
            }

            response.body().use { lines ->
                lines.forEach { line -> parser.feed(line) }
            }

            // Stream ended gracefully
            logger.info("ReverseSseTransport: SSE stream ended, will reconnect")
            scheduleReconnect()

        } catch (e: InterruptedException) {
            logger.info("ReverseSseTransport: reader thread interrupted")
        } catch (e: Exception) {
            if (!closed.get()) {
                logger.error("ReverseSseTransport: SSE error: {}", e.message)
                scheduleReconnect()
            }
        }
    }

    private fun onMessage(rawJson: String) {
        try {
            val mapper = JacksonMcpJsonMapper(objectMapper)
            val message = McpSchema.deserializeJsonRpcMessage(mapper, rawJson)
            currentSession.get()?.handle(message)?.subscribe()
        } catch (e: Exception) {
            logger.error("ReverseSseTransport: failed to parse SSE message: {}", e.message)
        }
    }

    private fun scheduleReconnect() {
        if (closed.get() || !options.reconnect.enabled) return
        reconnectManager.scheduleNext { attempt ->
            logger.info("ReverseSseTransport: reconnecting (attempt {})", attempt)
            startSseConnection()
        }
    }
}
