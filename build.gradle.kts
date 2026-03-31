plugins {
    kotlin("jvm") version "1.9.21"
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    
    // JSON for CLI mode
    implementation("com.google.code.gson:gson:2.10.1")
    
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
    
    // Don't minimize - keep all dependencies
}

kotlin {
    jvmToolchain(17)
}
