package io.github.chineseastar.mcpreverse.sse

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.chineseastar.mcpreverse.NoopLogger
import io.github.chineseastar.mcpreverse.ReverseLogger
import io.modelcontextprotocol.json.TypeRef
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpServerTransport
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import reactor.core.publisher.Mono

/**
 * [McpServerTransport] used on the **internal side** of a reverse MCP
 * channel. Sends outgoing JSON-RPC messages to the public acceptor via
 * HTTP POST and receives incoming messages via the SSE stream handled
 * by [ReverseSseTransport].
 */
class SseServerTransport(
    private val messageUrl: String,
    private val sessionId: String?,
    private val serverName: String,
    private val authToken: String?,
    private val httpClient: OkHttpClient,
    private val objectMapper: ObjectMapper,
    private val logger: ReverseLogger = NoopLogger,
) : McpServerTransport {

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    override fun sendMessage(message: McpSchema.JSONRPCMessage): Mono<Void> {
        return Mono.fromCallable {
            val json = objectMapper.writeValueAsString(message)
            val requestBuilder = Request.Builder()
                .url(messageUrl)
                .header("X-MCP-Server-Name", serverName)
                .post(json.toRequestBody(JSON))

            sessionId?.let { requestBuilder.header("X-Session-Id", it) }
            authToken?.let { requestBuilder.header("Authorization", "Bearer $it") }

            try {
                httpClient.newCall(requestBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        logger.warn("SseServerTransport: POST {} returned {}", messageUrl, response.code)
                    }
                }
            } catch (e: Exception) {
                logger.error("SseServerTransport: Failed to send message: {}", e.message)
            }
        }.then()
    }

    override fun closeGracefully(): Mono<Void> {
        return Mono.fromRunnable {
            try {
                val disconnectUrl = messageUrl.replaceAfterLast("/message", "disconnect")
                val request = Request.Builder()
                    .url(disconnectUrl)
                    .post(ByteArray(0).toRequestBody(null))
                    .build()
                httpClient.newCall(request).execute().use { /* discarding */ }
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
