package io.github.chineseastar.mcpreverse.sse

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.chineseastar.mcpreverse.NoopLogger
import io.github.chineseastar.mcpreverse.ReverseLogger
import io.modelcontextprotocol.json.TypeRef
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpServerTransport
import reactor.core.publisher.Mono
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse

/**
 * [McpServerTransport] used on the **internal side** of a reverse MCP
 * channel. Sends outgoing JSON-RPC messages to the public acceptor via
 * HTTP POST and receives incoming messages via the SSE stream handled
 * by [ReverseSseTransport].
 */
class SseServerTransport(
    private val messageUrl: String,
    private val httpClient: HttpClient,
    private val objectMapper: ObjectMapper,
    private val logger: ReverseLogger = NoopLogger,
) : McpServerTransport {

    override fun sendMessage(message: McpSchema.JSONRPCMessage): Mono<Void> {
        return Mono.fromCallable {
            val json = objectMapper.writeValueAsString(message)
            val request = HttpRequest.newBuilder()
                .uri(URI(messageUrl))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(json))
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.discarding())
            if (response.statusCode() !in 200..299) {
                logger.warn("SseServerTransport: POST {} returned {}", messageUrl, response.statusCode())
            }
        }.then()
    }

    override fun closeGracefully(): Mono<Void> {
        return Mono.fromRunnable {
            try {
                val disconnectUrl = messageUrl.replaceAfterLast("/message", "disconnect")
                val request = HttpRequest.newBuilder()
                    .uri(URI(disconnectUrl))
                    .POST(BodyPublishers.noBody())
                    .build()
                httpClient.send(request, HttpResponse.BodyHandlers.discarding())
            } catch (_: Exception) {
                // best-effort
            }
            logger.debug("SseServerTransport: closed ({})", messageUrl)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> unmarshalFrom(data: Any?, typeRef: TypeRef<T>): T {
        if (data == null) return null as T
        return objectMapper.convertValue(data, objectMapper.constructType(typeRef.type))
    }
}
