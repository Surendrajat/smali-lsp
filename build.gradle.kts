plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("antlr")
    id("jacoco")
    id("me.champeau.jmh") version "0.7.2"
}

group = "xyz.surendrajat"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // ANTLR for parsing
    antlr("org.antlr:antlr4:4.13.1")
    implementation("org.antlr:antlr4-runtime:4.13.1")
    
    // LSP4J for language server
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.21.1")
    
    // Kotlin coroutines for concurrency
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    
    // JSON for CLI mode
    implementation("com.google.code.gson:gson:2.10.1")

    // MCP (Model Context Protocol) server
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.10.0")
    
    // Logging
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.4.14")
    
    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.assertj:assertj-core:3.24.2")
    
    // JMH Benchmarking
    jmh("org.openjdk.jmh:jmh-core:1.37")
    jmh("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

// Configure ANTLR
tasks.generateGrammarSource {
    maxHeapSize = "128m"
    arguments = arguments + listOf("-visitor", "-long-messages", "-package", "xyz.surendrajat.smalilsp.parser.generated")
    outputDirectory = file("build/generated-src/antlr/main/xyz/surendrajat/smalilsp/parser/generated")
}

// Make Kotlin compilation depend on ANTLR generation
tasks.compileKotlin {
    dependsOn(tasks.generateGrammarSource)
}

tasks.compileTestKotlin {
    dependsOn(tasks.generateTestGrammarSource)
}

// Fix JMH task dependencies
tasks.named("compileJmhKotlin") {
    dependsOn(tasks.named("generateJmhGrammarSource"))
}

tasks.test {
    useJUnitPlatform()
    
    // Show test output
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
    
    // Parallel execution
    maxParallelForks = Runtime.getRuntime().availableProcessors()
    
    // Fail fast
    failFast = false
}

tasks.jacocoTestReport {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.shadowJar {
    archiveBaseName.set("smali-lsp")
    archiveClassifier.set("all")
    archiveVersion.set("1.0.0")

    manifest {
        attributes["Main-Class"] = "xyz.surendrajat.smalilsp.MainKt"
    }

    // --- Exclude build-time-only ANTLR4 tool classes ---
    // antlr("org.antlr:antlr4:4.13.1") leaks into runtimeClasspath via the ANTLR plugin.
    // Only antlr4-runtime (org/antlr/v4/runtime/**) is needed at runtime.
    // The tool packages below are used only during grammar code generation at build time.
    exclude("org/antlr/v4/Tool*.class")  // top-level Tool class
    exclude("org/antlr/v4/tool/**")
    exclude("org/antlr/v4/codegen/**")
    exclude("org/antlr/v4/analysis/**")
    exclude("org/antlr/v4/automata/**")
    exclude("org/antlr/v4/gui/**")
    exclude("org/antlr/v4/semantics/**")
    exclude("org/antlr/v4/parse/**")
    exclude("org/antlr/v4/misc/**")     // tool misc (runtime misc lives under org/antlr/v4/runtime/misc)

    // ANTLR3 runtime — transitive dep of the full antlr4 tool, not used at runtime
    exclude("org/antlr/runtime/**")

    // StringTemplate 4 — only used by ANTLR4 code generation, not at runtime
    exclude("org/stringtemplate/**")

    // Tree layout — only for ANTLR4 GUI visualization
    exclude("org/abego/**")

    // ICU4J — only used by ANTLR4 tool for unicode property analysis during grammar compilation
    exclude("com/ibm/**")

    // --- Exclude Ktor HTTP/WebSocket transport ---
    // The MCP Kotlin SDK includes Ktor for HTTP+SSE transport.
    // We only use StdioServerTransport. Ktor classes are never loaded on the stdio path
    // (class loading is lazy; KtorServerKt extension functions are never called).
    exclude("io/ktor/**")
    exclude("com/typesafe/**")  // Typesafe Config, pulled in by Ktor
}

kotlin {
    jvmToolchain(17)
}
