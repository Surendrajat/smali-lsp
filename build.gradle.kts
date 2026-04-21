import java.time.Instant

buildscript {
    dependencies {
        constraints {
            classpath(libs.commons.io)
            classpath(libs.plexus.utils)
            classpath(libs.log4j.api)
            classpath(libs.log4j.core)
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
    id("antlr")
    id("jacoco")
    alias(libs.plugins.jmh)
}

group = "xyz.surendrajat"
version = "1.5.0"

repositories {
    mavenCentral()
}

dependencies {
    // ANTLR for parsing
    antlr(libs.antlr.tool)
    implementation(libs.antlr.runtime)
    
    // LSP4J for language server
    implementation(libs.lsp4j)
    
    // Kotlin coroutines for concurrency
    implementation(libs.kotlinx.coroutines.core)
    
    // JSON for CLI mode
    implementation(libs.gson)

    // MCP (Model Context Protocol) server
    implementation(libs.mcp.kotlin.sdk.server)
    
    // Logging
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
    
    // Testing
    testImplementation(libs.junit.jupiter)
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    
    // JMH Benchmarking
    jmh(libs.jmh.core)
    jmh(libs.jmh.generator.annprocess)
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
    
    // Each test worker needs enough heap for large APK tests (indexing 4K+ files)
    maxHeapSize = "1g"
    
    // Limit parallelism: each worker holds a parsed APK in memory.
    // Too many forks OOM; too few wastes time. 4 is a safe default.
    maxParallelForks = minOf(4, Runtime.getRuntime().availableProcessors())
    
    // Fail on first test failure
    failFast = true
}

tasks.jacocoTestReport {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

// Generate version metadata resource from project version + git commit
val versionPropertiesDir = layout.buildDirectory.dir("generated-resources/version")
val versionPropertiesFile = versionPropertiesDir.map { it.file("version.properties") }
val gitCommitProvider = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }

tasks.register("generateVersionProperties") {
    inputs.property("projectVersion", project.version.toString())
    inputs.property("gitCommit", gitCommitProvider)
    outputs.file(versionPropertiesFile)
    doLast {
        val commitHash = try {
            gitCommitProvider.get()
        } catch (_: Exception) {
            "unknown"
        }

        val buildTime = Instant.now().toString()
        val file = versionPropertiesFile.get().asFile

        file.parentFile.mkdirs()
        file.writeText("""version=${project.version}
commit=$commitHash
buildTime=$buildTime
""")
    }
}

tasks.processResources {
    dependsOn("generateVersionProperties")
    from(versionPropertiesDir)
}

// The plain jar (classes-only, no deps) is useless — only the shadow fat jar is distributed.
tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveBaseName.set("smali-lsp")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())

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
