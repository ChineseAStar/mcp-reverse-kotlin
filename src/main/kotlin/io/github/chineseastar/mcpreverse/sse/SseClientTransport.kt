package io.github.chineseastar.mcpreverse.sse

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.chineseastar.mcpreverse.NoopLogger
import io.github.chineseastar.mcpreverse.ReverseLogger
import io.modelcontextprotocol.json.TypeRef
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper
import io.modelcontextprotocol.spec.McpClientTransport
import io.modelcontextprotocol.spec.McpSchema
import reactor.core.publisher.Mono
import java.util.function.Function

/**
 * [McpClientTransport] backed by an SSE connection, used on the **public
 * (acceptor) side** of the reverse MCP channel.
 *
 * - [sendMessage] writes an SSE `message` event into the [SseWriter].
 * - Incoming messages arrive via HTTP POST and must be delivered by calling
 *   [onIncomingMessage].
 */
class SseClientTransport(
    val connectionId: String,
    private val sseWriter: SseWriter,
    private val objectMapper: ObjectMapper,
    private val logger: ReverseLogger = NoopLogger,
) : McpClientTransport {

    @Volatile
    private var incomingHandler: Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>>? = null

    // ── McpClientTransport ────────────────────────────────────────

    override fun connect(handler: Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>>): Mono<Void> {
        this.incomingHandler = handler
        return Mono.empty()
    }

    override fun sendMessage(message: McpSchema.JSONRPCMessage): Mono<Void> {
        return Mono.fromRunnable {
            try {
                val json = objectMapper.writeValueAsString(message)
                sseWriter.sendEvent("message", json)
            } catch (e: Exception) {
                logger.error("SseClientTransport: failed to serialize message: {}", e.message)
                throw RuntimeException("Failed to serialize JSON-RPC message", e)
            }
        }
    }

    override fun closeGracefully(): Mono<Void> {
        return Mono.fromRunnable {
            logger.debug("SseClientTransport: closing connection {}", connectionId)
            sseWriter.close()
        }
    }

    // ── Internal (called by SseAcceptor when a message arrives via POST) ─

    /**
     * Called by [SseAcceptor] when the internal side POSTs a JSON-RPC message.
     * Deserializes and forwards it through the MCP handler chain.
     */
    fun onIncomingMessage(rawJson: String) {
        val handler = incomingHandler ?: run {
            logger.warn("SseClientTransport: no handler set yet, dropping message on {}", connectionId)
            return
        }
        try {
            val mapper = JacksonMcpJsonMapper(objectMapper)
            val message = McpSchema.deserializeJsonRpcMessage(mapper, rawJson)
            handler.apply(Mono.just(message)).subscribe()
        } catch (e: Exception) {
            logger.error("SseClientTransport: failed to parse incoming message: {}", e.message)
        }
    }

    // ── unmarshal ──────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> unmarshalFrom(data: Any?, typeRef: TypeRef<T>): T {
        if (data == null) return null as T
        return objectMapper.convertValue(data, objectMapper.constructType(typeRef.type))
    }
}
