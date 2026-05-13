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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import reactor.core.publisher.Mono
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ReverseSseTransport(
    private val options: ReverseSseOptions,
    private val objectMapper: ObjectMapper = ObjectMapper(),
    private val logger: ReverseLogger = NoopLogger,
) : McpServerTransportProvider {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(options.connectionTimeout.toMillis(), TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // SSE needs 0 for infinite stream
        .build()

    private val reconnectManager = ReconnectManager(options.reconnect, logger)
    private val sessionFactoryRef = AtomicReference<McpServerSession.Factory>()
    private val currentSession = AtomicReference<McpServerSession>()
    private val currentEventSource = AtomicReference<EventSource>()
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
            currentEventSource.get()?.cancel()
            logger.info("ReverseSseTransport: closed for server '{}'", options.serverName)
        }
    }

    // ── Internal ───────────────────────────────────────────────────

    private fun startSseConnection() {
        if (closed.get()) return

        val factory = sessionFactoryRef.get() ?: run {
            logger.error("ReverseSseTransport: sessionFactory not set")
            return
        }

        val url = "${options.acceptorUrl}/sse?server_name=${options.serverName}"

        val requestBuilder = Request.Builder()
            .url(url)
            .header("X-MCP-Server-Name", options.serverName)
            .header("Accept", "text/event-stream")
            .get()

        options.authToken?.let { token ->
            requestBuilder.header("Authorization", "Bearer $token")
        }

        val request = requestBuilder.build()
        val factorySource = EventSources.createFactory(httpClient)

        val eventSourceListener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                // Session ID: prefer server-assigned, fall back to client-generated
                val serverSessionId = response.header("x-session-id")
                val sessionId = serverSessionId
                    ?: "${options.serverName}-${System.currentTimeMillis()}-${(Math.random() * 1e7).toLong()}"
                logger.debug("ReverseSseTransport: sessionId={}", sessionId)

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
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                when (type) {
                    "message", null -> onMessage(data)
                    else -> logger.debug("ReverseSseTransport: ignoring SSE event '{}'", type)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                logger.info("ReverseSseTransport: SSE stream ended, will reconnect")
                scheduleReconnect()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (!closed.get()) {
                    logger.error("ReverseSseTransport: SSE error: {}", t?.message ?: "HTTP ${response?.code}")
                    scheduleReconnect()
                }
            }
        }

        val eventSource = factorySource.newEventSource(request, eventSourceListener)
        currentEventSource.set(eventSource)
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
