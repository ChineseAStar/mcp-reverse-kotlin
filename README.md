# mcp-reverse-kotlin

[![Release](https://jitpack.io/v/chineseastar/mcp-reverse-kotlin.svg)](https://jitpack.io/#chineseastar/mcp-reverse-kotlin)

Reverse MCP transport for Kotlin/Java — **SSE engine**. Lets an MCP server behind NAT/firewall connect **out** to a public MCP client.

## Why

Normal MCP: the client connects to the server.  
**Reverse MCP**: the server connects out to the client.

```
Internal (NAT behind)                    Public (VPS / cloud)
┌──────────────────┐                    ┌──────────────────────┐
│ jadx-mcp Server  │  ─── SSE GET ───►  │ chat-ai (MCP Client) │
│  ReverseSseTransport                  │  SseAcceptor          │
│                   │  ◄── SSE events ─ │                       │
│                   │  ─── POST /msg ─► │                       │
└──────────────────┘                    └──────────────────────┘
```

## Installation

### 1. Add JitPack repository

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
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
    implementation("com.github.chineseastar:mcp-reverse-kotlin:0.1.2")
}
```

> **Important**: JitPack coordinates are `com.github.chineseastar:mcp-reverse-kotlin:VERSION`.  
> The `io.github.chineseastar` group in `gradle.properties` is POM metadata only — JitPack overrides the groupId to `com.github.chineseastar`.  
> If this library later publishes to Maven Central, the coordinates will be `io.github.chineseastar:mcp-reverse-kotlin:VERSION`.

## Usage

### Internal side (MCP server behind NAT, e.g. jadx-mcp)

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
        serverName = "jadx-mcp",
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
    .serverInfo(Implementation("jadx-mcp", "1.0"))
    .build()

// Register your tools/resources/prompts as usual
server.addTool(McpSchema.Tool("decompile", "Decompile a class", jsonSchema))
    .doOnExecute { params -> /* ... */ }

// The server is now connected out to the public side.
// No need to listen on a port.
```

### Public side (MCP client, e.g. chat-ai)

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
    val writer = JavalinSseWriter(ctx)  // you implement SseWriter
    val transport = acceptor.acceptConnection(serverName, writer)
    val client: McpSyncClient = McpClient.sync(transport).build()
    // client is now connected to the internal server — call tools, resources, etc.
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

#### SseWriter implementation for Javalin

```kotlin
import io.github.chineseastar.mcpreverse.sse.SseWriter
import io.javalin.http.Context

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

## How JitPack Publishing Works

You **do not** need GitHub Actions, GPG keys, Sonatype accounts, or any secrets to publish.

JitPack watches your GitHub repository. When you push a git tag, it automatically:

1. Clones the repo at that tag
2. Reads `jitpack.yml` to determine the JDK version
3. Runs `./gradlew build publishToMavenLocal`
4. Serves the built artifacts from its CDN

```
git tag 0.1.2
git push origin 0.1.2     ← that's it!
```

Check build status at: `https://jitpack.io/#ChineseAStar/mcp-reverse-kotlin/0.1.2`

### jitpack.yml

```yaml
jdk:
  - openjdk21
```

This tells JitPack to use JDK 21 for building. The compiled bytecode targets JVM 11.

## Releasing a New Version

```bash
# 1. Edit version in gradle.properties
#    version=0.1.0  →  version=0.2.0

# 2. Commit
git add gradle.properties
git commit -m "Release 0.2.0"
git push

# 3. Tag
git tag 0.2.0
git push origin 0.2.0

# Done. JitPack builds automatically.
# Check: https://jitpack.io/com/github/chineseastar/mcp-reverse-kotlin/
```

If you make a mistake, delete the tag and re-tag:
```bash
git tag -d 0.2.0
git push origin :0.2.0
# ... fix ... then re-tag
```

## Snapshot Builds

To test the latest commit without making a release:

```kotlin
implementation("com.github.chineseastar:mcp-reverse-kotlin:main-SNAPSHOT")
```

Or use a specific commit hash:

```kotlin
implementation("com.github.chineseastar:mcp-reverse-kotlin:abc1234")
```

## Project Structure

```
src/main/kotlin/io/github/chineseastar/mcpreverse/
├── McpReverseTypes.kt              # Options, logger interface
├── McpReverseReconnect.kt          # Exponential backoff reconnect
├── ReverseMCPClient.kt             # High-level builder API
└── sse/
    ├── SseWriter.kt                # SSE output abstraction
    ├── SseParser.kt                # SSE stream parser
    ├── SseAcceptor.kt              # Public-side connection acceptor
    ├── SseClientTransport.kt       # McpClientTransport (public side)
    ├── SseServerTransport.kt       # McpServerTransport (internal side)
    └── ReverseSseTransport.kt      # McpServerTransportProvider (internal)
```

## Dependencies

Everything is **compileOnly** — your project provides them at runtime:

| Dependency | Purpose |
|------------|---------|
| `mcp-core:1.1.2` | MCP transport interfaces |
| `mcp-json-jackson2:1.1.2` | JSON-RPC message serialization |
| `slf4j-api:2.0.x` | Logging facade |

No other dependencies. HTTP/SSE uses JDK 11+ built-in `java.net.http.HttpClient`.

## License

MIT
