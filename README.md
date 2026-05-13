# mcp-reverse-kotlin

[![Release](https://jitpack.io/v/chineseastar/mcp-reverse-kotlin.svg)](https://jitpack.io/#chineseastar/mcp-reverse-kotlin)

A cross-platform Reverse MCP transport for Kotlin/Java/Android — **SSE engine**. 
Lets an MCP server behind a NAT/firewall connect **out** to a public MCP client.

## Why Reverse MCP?

Normal MCP: The client connects to the server.  
**Reverse MCP**: The server connects out to the client.

```text
Internal (NAT/Android/Local)             Public (VPS / Cloud)
┌──────────────────────┐                ┌──────────────────────┐
│ android-mcp Server   │ ── SSE GET ──► │ chat-ai (MCP Client) │
│ ReverseSseTransport  │                │ SseAcceptor          │
│                      │ ◄─ SSE events─ │                      │
│                      │ ── POST /msg ─►│                      │
└──────────────────────┘                └──────────────────────┘
```

## Features

- **Cross-Platform**: Uses `OkHttp3` internally. Runs perfectly on **Java 8+**, **Spring Boot**, and **Android** (API 21+).
- **No Listen Port**: The internal server acts as an HTTP client, easily bypassing inbound firewall restrictions.
- **Robust SSE**: Uses official `okhttp-sse` for rock-solid event stream parsing. Built-in connection lifecycle & auto-reconnect management.

## Installation

### 1. Add JitPack repository

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

### 2. Add dependency

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.github.chineseastar:mcp-reverse-kotlin:0.2.0")
}
```

## Usage

### Internal side (MCP Server behind NAT / Android Device)

```kotlin
import io.github.chineseastar.mcpreverse.ReverseSseOptions
import io.github.chineseastar.mcpreverse.ReconnectOptions
import io.github.chineseastar.mcpreverse.sse.ReverseSseTransport
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.spec.McpSchema.Implementation
import java.time.Duration

val transport = ReverseSseTransport(
    ReverseSseOptions(
        acceptorUrl = "http://your-public-host:8080/mcp-reverse",
        serverName = "android-mcp",
        authToken = null,          // optional Bearer token
        reconnect = ReconnectOptions(
            enabled = true,
            maxAttempts = 0,       // 0 = unlimited
            baseDelay = Duration.ofSeconds(1),
            maxDelay = Duration.ofSeconds(30),
        ),
    )
)

val server = McpServer.sync(transport)
    .serverInfo(Implementation("android-mcp", "1.0"))
    .build()

// Register your tools as usual
server.addTool(...) { ... }

// The server is now connected out to the public side!
```

### Public side (MCP Client on VPS)

Any HTTP framework works. Example with Javalin:

```kotlin
import io.github.chineseastar.mcpreverse.sse.SseAcceptor
import io.github.chineseastar.mcpreverse.sse.SseWriter
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient

val acceptor = SseAcceptor()

// GET /mcp-reverse/sse?serverName=xxx
app.get("/mcp-reverse/sse") { ctx ->
    val serverName = ctx.queryParam("serverName") ?: "unknown"
    val writer = JavalinSseWriter(ctx) // You implement SseWriter (see below)
    
    // Accept connection and get the Transport
    val transport = acceptor.acceptConnection(serverName, writer)
    
    // Bind to official MCP Client
    val client: McpSyncClient = McpClient.sync(transport).build()
    
    // Client is now connected to the NAT server!
}

// POST /mcp-reverse/message?connectionId=xxx
app.post("/mcp-reverse/message") { ctx ->
    val connectionId = ctx.queryParam("connectionId")!!
    acceptor.handleMessage(connectionId, ctx.body())
    ctx.status(202)
}

// POST /mcp-reverse/disconnect?connectionId=xxx
app.post("/mcp-reverse/disconnect") { ctx ->
    val connectionId = ctx.queryParam("connectionId")!!
    acceptor.removeConnection(connectionId)
    ctx.status(200)
}
```

#### SseWriter implementation for Javalin (Example)

```kotlin
class JavalinSseWriter(private val ctx: Context) : SseWriter {
    init {
        ctx.header("Content-Type", "text/event-stream")
        ctx.header("Cache-Control", "no-cache")
        ctx.header("Connection", "keep-alive")
    }

    override fun sendEvent(event: String, data: String) {
        ctx.result("event: $event\ndata: $data\n\n")
        ctx.res.outputStream.flush()
    }

    override fun end() {
        ctx.result("event: done\ndata: \n\n")
        ctx.res.outputStream.flush()
    }

    override fun close() {
        try { ctx.res.outputStream.close() } catch (_: Exception) {}
    }
}
```

## Dependencies

- **MCP Base**: `mcp-core:1.1.2`, `mcp-json-jackson2:1.1.2` *(compileOnly - provided by user)*
- **Logging**: `slf4j-api:2.0.x` *(compileOnly)*
- **Networking**: `okhttp3`, `okhttp-sse` 5.x *(implementation)*

## License

MIT
