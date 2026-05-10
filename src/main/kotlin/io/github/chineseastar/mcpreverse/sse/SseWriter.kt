package io.github.chineseastar.mcpreverse.sse

/**
 * Abstraction over an SSE output stream. The consumer implements this to
 * bridge into their HTTP framework (Javalin, Servlet, Netty, etc.).
 */
interface SseWriter {
    /** Write an SSE event. Handles formatting (event: ...\ndata: ...\n\n). */
    fun sendEvent(event: String, data: String)

    /** Signal the end of the SSE stream to the client. */
    fun end()

    /** Close the underlying connection / response. */
    fun close()
}
