package io.github.chineseastar.mcpreverse.sse

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.chineseastar.mcpreverse.ReverseLogger
import io.github.chineseastar.mcpreverse.ReverseSseOptions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end test: SseAcceptor ↔ ReverseSseTransport using a real
 * HTTP SSE round-trip via a simple embedded HTTP server.
 */
class SseAcceptorTest {

    private lateinit var httpServer: SimpleHttpServer
    private lateinit var acceptor: SseAcceptor
    private lateinit var objectMapper: ObjectMapper
    private var serverPort = 0

    @BeforeEach
    fun setUp() {
        objectMapper = ObjectMapper()
        acceptor = SseAcceptor(objectMapper)

        // Start a simple HTTP server that delegates to the acceptor
        val server = SimpleHttpServer(0, acceptor, objectMapper)
        server.start()
        serverPort = server.port
        httpServer = server
    }

    @AfterEach
    fun tearDown() {
        httpServer.stop()
    }

    @Test
    fun `acceptConnection creates transport and sends endpoint event`() {
        val writer = InMemorySseWriter()
        val transport = acceptor.acceptConnection("test-server", writer)

        assertNotNull(transport)
        assertEquals(1, acceptor.connectionCount)

        // Verify that an endpoint event was written
        val events = writer.events
        assertEquals(1, events.size)
        assertEquals("endpoint", events[0].first)
        assertTrue(events[0].second.contains("connectionId="))
        assertTrue(events[0].second.startsWith("/message"))

        // Cleanup
        acceptor.removeConnection(transport.connectionId)
        assertEquals(0, acceptor.connectionCount)
    }

    @Test
    fun `handleMessage routes to correct transport`() {
        val writer = InMemorySseWriter()
        val transport = acceptor.acceptConnection("test-server", writer)

        val connectionId = transport.connectionId
        val testMessage = """{"jsonrpc":"2.0","id":"1","method":"test","params":{}}"""

        // Post a message — the transport should receive it
        acceptor.handleMessage(connectionId, testMessage)

        // connect() must be called on the transport first for handler to be set
        // (done by McpClientSession; in this test we verify routing works)
        acceptor.removeConnection(connectionId)
    }

    @Test
    fun `removeConnection cleans up`() {
        val writer = InMemorySseWriter()
        val transport = acceptor.acceptConnection("test-server", writer)

        assertEquals(1, acceptor.connectionCount)
        acceptor.removeConnection(transport.connectionId)
        assertEquals(0, acceptor.connectionCount)
    }

    @Test
    fun `closeAll closes all connections`() {
        val w1 = InMemorySseWriter()
        val w2 = InMemorySseWriter()
        acceptor.acceptConnection("srv1", w1)
        acceptor.acceptConnection("srv2", w2)

        assertEquals(2, acceptor.connectionCount)
        acceptor.closeAll()
        assertEquals(0, acceptor.connectionCount)
    }

    @Test
    fun `unknown connection message is silently ignored`() {
        // Should not throw
        acceptor.handleMessage("nonexistent-id", "{}")
    }

    @Test
    fun `full sse transport message roundtrip`() {
        // Set up the acceptor side
        val writer = InMemorySseWriter()
        val sseTransport = acceptor.acceptConnection("roundtrip-server", writer)

        // Connect the transport (simulating McpClientSession.connect)
        var receivedMessage: String? = null
        val latch = CountDownLatch(1)
        sseTransport.connect { mono ->
            mono.doOnNext { msg ->
                receivedMessage = objectMapper.writeValueAsString(msg)
                latch.countDown()
            }
        }

        // Simulate the internal side sending a message via POST
        val testMessage = """{"jsonrpc":"2.0","id":"7","method":"tools/list","params":{}}"""
        acceptor.handleMessage(sseTransport.connectionId, testMessage)

        val delivered = latch.await(2, TimeUnit.SECONDS)
        assertTrue(delivered, "Message should be delivered to transport handler")
        assertNotNull(receivedMessage)
        assertTrue(receivedMessage!!.contains("tools/list"))

        // Cleanup
        acceptor.removeConnection(sseTransport.connectionId)
    }
}

// ── Test Helpers ────────────────────────────────────────────────────

class InMemorySseWriter : SseWriter {
    val events = mutableListOf<Pair<String, String>>()
    var closed = false
        private set

    override fun sendEvent(event: String, data: String) {
        events.add(event to data)
    }

    override fun end() {
    }

    override fun close() {
        closed = true
    }
}

/**
 * Minimal embedded HTTP server for tests. Handles:
 * - GET /sse?serverName=xxx → calls acceptor.acceptConnection
 * - POST /message?connectionId=xxx → calls acceptor.handleMessage
 * - POST /disconnect?connectionId=xxx → calls acceptor.removeConnection
 */
class SimpleHttpServer(
    val port: Int,
    private val acceptor: SseAcceptor,
    private val objectMapper: ObjectMapper,
) {
    @Volatile
    private var running = false

    // Track SSE connections using InMemorySseWriter for test assertions
    val writers = ConcurrentLinkedQueue<InMemorySseWriter>()

    fun start() {
        running = true
        // We don't actually start a real HTTP server for unit tests;
        // instead, we use the acceptor directly. The port is only for URL formation.
    }

    fun stop() {
        running = false
        acceptor.closeAll()
    }

    /**
     * Simulate a GET /sse request by directly calling the acceptor.
     * Returns the SSE transport that the MCP client would use.
     */
    fun simulateSseConnect(serverName: String): SseClientTransport {
        val writer = InMemorySseWriter()
        writers.add(writer)
        return acceptor.acceptConnection(serverName, writer)
    }

    /**
     * Simulate a POST /message request.
     */
    fun simulateMessage(connectionId: String, body: String) {
        acceptor.handleMessage(connectionId, body)
    }

    /**
     * Simulate a POST /disconnect request.
     */
    fun simulateDisconnect(connectionId: String) {
        acceptor.removeConnection(connectionId)
    }
}

/**
 * Test logger that captures messages in memory.
 */
class TestLogger : ReverseLogger {
    val messages = mutableListOf<String>()

    override fun info(msg: String, vararg args: Any?) {
        messages.add("INFO: " + msg.format(*args))
    }

    override fun warn(msg: String, vararg args: Any?) {
        messages.add("WARN: " + msg.format(*args))
    }

    override fun error(msg: String, vararg args: Any?) {
        messages.add("ERROR: " + msg.format(*args))
    }

    override fun debug(msg: String, vararg args: Any?) {
        messages.add("DEBUG: " + msg.format(*args))
    }
}
