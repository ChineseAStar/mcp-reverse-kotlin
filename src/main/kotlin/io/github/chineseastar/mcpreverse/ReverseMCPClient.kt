package io.github.chineseastar.mcpreverse

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.chineseastar.mcpreverse.sse.ReverseSseTransport
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpSyncServer
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpServerTransportProvider
import reactor.core.publisher.Mono

/**
 * High-level client for creating an MCP server that connects out to a public
 * acceptor via reverse SSE transport.
 *
 * Wraps [McpServer.sync] with reverse-transport setup and provides a
 * builder-style API for common configuration.
 *
 * ### Usage:
 * ```kotlin
 * val server = ReverseMCPClient.sse {
 *     acceptorUrl = "http://chat-ai:8080/mcp-reverse"
 *     serverName = "jadx-mcp"
 *     serverInfo = McpSchema.Implementation("jadx-mcp", "1.0")
 *     reconnect { enabled = true }
 *     onTool("decompile") { params -> "result" }
 * }
 * ```
 */
object ReverseMCPClient {

    /**
     * Build an [McpSyncServer] backed by a reverse SSE transport.
     * Returns the server which can then have tools, resources, and prompts registered.
     */
    fun buildServer(
        options: ReverseSseOptions,
        serverInfo: McpSchema.Implementation = McpSchema.Implementation("mcp-reverse-kotlin", "unknown"),
        objectMapper: ObjectMapper = ObjectMapper(),
        logger: ReverseLogger = NoopLogger,
    ): McpSyncServer {
        val transport: McpServerTransportProvider = ReverseSseTransport(options, objectMapper, logger)
        return McpServer.sync(transport)
            .serverInfo(serverInfo)
            .build()
    }
}
