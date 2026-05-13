plugins {
    kotlin("jvm") version "2.2.21"
    `maven-publish`
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
    withJavadocJar()
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = JavaVersion.VERSION_11.toString()
    targetCompatibility = JavaVersion.VERSION_11.toString()
}

dependencies {
    // MCP SDK (provided by the consuming project)
    compileOnly("io.modelcontextprotocol.sdk:mcp-core:1.1.2")
    compileOnly("io.modelcontextprotocol.sdk:mcp-json-jackson2:1.1.2")

    // SLF4J logging facade
    compileOnly("org.slf4j:slf4j-api:2.0.18")

    // OkHttp (Cross-platform HTTP & SSE)
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.squareup.okhttp3:okhttp-sse:5.3.2")

    // Test dependencies
    testImplementation("io.modelcontextprotocol.sdk:mcp-core:1.1.2")
    testImplementation("io.modelcontextprotocol.sdk:mcp-json-jackson2:1.1.2")
    testImplementation("org.slf4j:slf4j-api:2.0.17")
    testImplementation("org.slf4j:slf4j-simple:2.0.17")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

tasks.test {
    useJUnitPlatform()
}

// ── Publishing (for JitPack & local use) ───────────────────────────

val projectGroup = project.group.toString()
val projectVersion = project.version.toString()

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = projectGroup
            artifactId = "mcp-reverse-kotlin"
            version = projectVersion

            pom {
                name.set("mcp-reverse-kotlin")
                description.set("Reverse MCP transport library for Java/Kotlin — SSE engine. Lets MCP servers behind NAT connect out to public MCP clients.")
                url.set("https://github.com/chineseastar/mcp-reverse-kotlin")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("chineseastar")
                        name.set("Chinese Astar")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/chineseastar/mcp-reverse-kotlin.git")
                    developerConnection.set("scm:git:ssh://github.com/chineseastar/mcp-reverse-kotlin.git")
                    url.set("https://github.com/chineseastar/mcp-reverse-kotlin")
                }
            }
        }
    }
}
