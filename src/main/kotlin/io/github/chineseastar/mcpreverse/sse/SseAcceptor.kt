package io.github.chineseastar.mcpreverse.sse

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.chineseastar.mcpreverse.NoopLogger
import io.github.chineseastar.mcpreverse.ReverseLogger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Public-side SSE acceptor. Manages incoming SSE connections from
 * internal-side MCP servers and creates [McpClientTransport] instances
 * that MCP clients ([io.modelcontextprotocol.client.McpClient]) can use.
 *
 * ### Usage with an HTTP framework (e.g. Javalin):
 * ```kotlin
 * val acceptor = SseAcceptor()
 *
 * // GET /mcp-reverse/sse?serverName=xxx
 * app.get("/mcp-reverse/sse") { ctx ->
 *     val writer = JavalinSseWriter(ctx)
 *     val transport = acceptor.acceptConnection(
 *         serverName = ctx.queryParam("serverName") ?: "unknown",
 *         sseWriter = writer
 *     )
 *     // Use `transport` with McpClient.sync(transport)
 * }
 *
 * // POST /mcp-reverse/message?connectionId=xxx
 * app.post("/mcp-reverse/message") { ctx ->
 *     val connectionId = ctx.queryParam("connectionId")!!
 *     acceptor.handleMessage(connectionId, ctx.body())
 *     ctx.status(202)
 * }
 * ```
 */
class SseAcceptor(
    private val objectMapper: ObjectMapper = ObjectMapper(),
    private val logger: ReverseLogger = NoopLogger,
) {
    private val connections = ConcurrentHashMap<String, SseClientTransport>()

    /**
     * Accept a new SSE connection. Writes the `endpoint` event to [sseWriter]
     * and returns the [SseClientTransport] that the MCP client should use.
     */
    fun acceptConnection(serverName: String, sseWriter: SseWriter): SseClientTransport {
        val connectionId = UUID.randomUUID().toString()
        logger.info("SseAcceptor: new connection {} from server '{}'", connectionId, serverName)

        val transport = SseClientTransport(
            connectionId = connectionId,
            sseWriter = sseWriter,
            objectMapper = objectMapper,
            logger = logger,
        )

        connections[connectionId] = transport

        // Send the endpoint event so the internal side knows where to POST messages
        sseWriter.sendEvent("endpoint", "/message?connectionId=$connectionId")

        return transport
    }

    /**
     * Handle an incoming JSON-RPC message POSTed by the internal side.
     */
    fun handleMessage(connectionId: String, body: String) {
        val transport = connections[connectionId]
        if (transport == null) {
            logger.warn("SseAcceptor: message for unknown connection {}", connectionId)
            return
        }
        transport.onIncomingMessage(body)
    }

    /**
     * Clean up a connection (call when the SSE stream is closed).
     */
    fun removeConnection(connectionId: String) {
        connections.remove(connectionId)
        logger.info("SseAcceptor: connection {} removed", connectionId)
    }

    /**
     * Close all active connections.
     */
    fun closeAll() {
        connections.values.forEach { it.closeGracefully().subscribe() }
        connections.clear()
    }

    /** Number of active connections. */
    val connectionCount: Int get() = connections.size
}
