package io.github.chineseastar.mcpreverse.sse

/**
 * Incremental SSE (Server-Sent Events) text-stream parser.
 *
 * Feed it one line at a time (from JDK HttpClient BodyHandlers.ofLines()).
 * When a complete event is assembled (blank line terminator), the callback is invoked.
 *
 * Supports multi-line data (data: ...\ndata: ... → concatenated with \n).
 */
class SseParser(
    private val callback: (event: String, data: String) -> Unit,
) {
    private var eventType = ""
    private val dataBuffer = StringBuilder()
    private var pending = false

    /**
     * Feed one line from the SSE stream. Empty lines trigger event emission.
     */
    fun feed(line: String) {
        when {
            line.startsWith("event:") -> {
                eventType = line.removePrefix("event:").trim()
            }
            line.startsWith("data:") -> {
                if (dataBuffer.isNotEmpty()) dataBuffer.append('\n')
                dataBuffer.append(line.removePrefix("data:").trim())
                pending = true
            }
            line.startsWith("id:") -> {
                // Store id for reconnect support (reserved for future use)
            }
            line.startsWith("retry:") -> {
                // Reconnection time hint from server (reserved for future use)
            }
            line.startsWith(":") -> {
                // Comment line — ignore
            }
            line.isEmpty() -> {
                if (pending) {
                    callback(eventType, dataBuffer.toString())
                    eventType = ""
                    dataBuffer.clear()
                    pending = false
                }
            }
        }
    }
}
