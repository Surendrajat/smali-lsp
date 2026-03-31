package xyz.surendrajat.smalilsp.cli

import com.google.gson.Gson
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.*
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Comprehensive tests for DaemonMode.
 * 
 * Tests all 9 commands:
 * - index
 * - find-definition
 * - find-references
 * - search-symbols
 * - hover
 * - diagnostics
 * - document-symbols
 * - get-stats
 * - shutdown (implicit)
 * 
 * Also tests error handling, URI normalization, and edge cases.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DaemonModeTest {

    private val gson = Gson()
    
    private lateinit var testProjectDir: File
    private lateinit var smaliFile: File
    private lateinit var smaliFileUri: String
    
    @BeforeEach
    fun setup() {
        // Create test project structure
        val tempDir = Files.createTempDirectory("daemon-test")
        testProjectDir = tempDir.resolve("test-project").toFile()
        testProjectDir.mkdirs()
        
        val smaliDir = File(testProjectDir, "smali/com/example/test")
        smaliDir.mkdirs()
        
        // Create a test smali file
        smaliFile = File(smaliDir, "MainActivity.smali")
        smaliFile.writeText("""
            .class public Lcom/example/test/MainActivity;
            .super Ljava/lang/Object;
            .source "MainActivity.java"
            
            # static fields
            .field private static TAG:Ljava/lang/String; = "MainActivity"
            
            # instance fields
            .field private counter:I
            
            # direct methods
            .method public constructor <init>()V
                .registers 2
                
                invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                
                const/4 v0, 0x0
                iput v0, p0, Lcom/example/test/MainActivity;->counter:I
                
                return-void
            .end method
            
            .method public static main([Ljava/lang/String;)V
                .registers 3
                
                sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;
                
                const-string v1, "Hello World"
                
                invoke-virtual {v0, v1}, Ljava/io/PrintStream;->println(Ljava/lang/String;)V
                
                return-void
            .end method
            
            .method public increment()V
                .registers 2
                
                iget v0, p0, Lcom/example/test/MainActivity;->counter:I
                add-int/lit8 v0, v0, 0x1
                iput v0, p0, Lcom/example/test/MainActivity;->counter:I
                
                return-void
            .end method
        """.trimIndent())
        
        // Use File.toURI() for proper URI format, then convert to string
        smaliFileUri = smaliFile.toURI().toString()
    }
    
    /**
     * Helper to send command and get response.
     */
    private fun sendCommand(
        stdin: BufferedWriter,
        stdout: BufferedReader,
        command: String,
        args: Map<String, String> = emptyMap()
    ): Map<*, *> {
        val request = mapOf("command" to command, "args" to args)
        val json = gson.toJson(request)
        
        stdin.write(json)
        stdin.newLine()
        stdin.flush()
        
        val response = stdout.readLine()
        assertNotNull(response, "Expected response but got null")
        
        return gson.fromJson(response, Map::class.java)
    }
    
    @Test
    fun `test full daemon lifecycle`() {
        // Start daemon process
        val process = startDaemonProcess()
        
        try {
            val stdin = BufferedWriter(OutputStreamWriter(process.outputStream))
            val stdout = BufferedReader(InputStreamReader(process.inputStream))
            val stderr = BufferedReader(InputStreamReader(process.errorStream))
            
            // Read stderr in background to capture "daemon ready"
            val stderrLines = mutableListOf<String>()
            thread {
                stderr.forEachLine { stderrLines.add(it) }
            }
            
            // Wait for daemon ready (max 5 seconds)
            val startTime = System.currentTimeMillis()
            while (!stderrLines.any { it.contains("daemon ready") }) {
                Thread.sleep(100)
                if (System.currentTimeMillis() - startTime > 5000) {
                    fail<Unit>("Daemon did not become ready within 5 seconds")
                }
            }
            
            // Test 1: Index
            val indexResponse = sendCommand(
                stdin, stdout,
                "index",
                mapOf("directory" to testProjectDir.absolutePath)
            )
            
            assertEquals(true, indexResponse["success"])
            assertNotNull(indexResponse["indexing"])
            
            @Suppress("UNCHECKED_CAST")
            val indexing = indexResponse["indexing"] as Map<String, Any?>
            assertTrue((indexing["filesProcessed"] as Number).toDouble() > 0)
            
            // Test 2: Get stats
            val statsResponse = sendCommand(stdin, stdout, "get-stats")
            
            assertEquals(true, statsResponse["success"])
            @Suppress("UNCHECKED_CAST")
            val stats = statsResponse["stats"] as Map<String, Any?>
            assertEquals(1.0, (stats["classes"] as Number).toDouble())
            assertTrue((stats["methods"] as Number).toDouble() >= 3.0) // constructor, main, increment
            assertTrue((stats["fields"] as Number).toDouble() >= 2.0) // TAG, counter
            
            // Test 3: Find definition
            val defResponse = sendCommand(
                stdin, stdout,
                "find-definition",
                mapOf("symbol" to "Lcom/example/test/MainActivity;")
            )
            
            assertEquals(true, defResponse["success"])
            @Suppress("UNCHECKED_CAST")
            val results = defResponse["results"] as List<Map<String, Any?>>
            assertEquals(1, results.size)
            assertEquals("class", results[0]["type"])
            assertEquals("Lcom/example/test/MainActivity;", results[0]["name"])
            
            // Test 4: Search symbols
            val searchResponse = sendCommand(
                stdin, stdout,
                "search-symbols",
                mapOf("pattern" to "main")
            )
            
            assertEquals(true, searchResponse["success"])
            @Suppress("UNCHECKED_CAST")
            val searchResults = searchResponse["results"] as List<Map<String, Any?>>
            assertTrue(searchResults.any { it["name"] == "main" })
            
            // Test 5: Document symbols
            val symbolsResponse = sendCommand(
                stdin, stdout,
                "document-symbols",
                mapOf("uri" to smaliFileUri)
            )
            
            assertEquals(true, symbolsResponse["success"])
            @Suppress("UNCHECKED_CAST")
            val symbols = symbolsResponse["symbols"] as List<Map<String, Any?>>
            assertTrue(symbols.size >= 4) // class + methods + fields
            assertTrue(symbols.any { it["kind"] == "class" })
            assertTrue(symbols.any { it["kind"] == "method" })
            assertTrue(symbols.any { it["kind"] == "field" })
            
            // Test 6: Hover (on counter field reference)
            val hoverResponse = sendCommand(
                stdin, stdout,
                "hover",
                mapOf(
                    "uri" to smaliFileUri,
                    "line" to "17", // Line with counter field
                    "character" to "50"
                )
            )
            
            assertEquals(true, hoverResponse["success"])
            // Hover might be null if position doesn't hit a symbol
            
            // Test 7: Find references (counter field)
            val refsResponse = sendCommand(
                stdin, stdout,
                "find-references",
                mapOf(
                    "uri" to smaliFileUri,
                    "line" to "17",
                    "character" to "50"
                )
            )
            
            assertEquals(true, refsResponse["success"])
            // References might be empty or have multiple entries
            
            // Test 8: Diagnostics
            val diagResponse = sendCommand(
                stdin, stdout,
                "diagnostics",
                mapOf(
                    "uri" to smaliFileUri,
                    "content" to smaliFile.readText()
                )
            )
            
            assertEquals(true, diagResponse["success"])
            @Suppress("UNCHECKED_CAST")
            val diagnostics = diagResponse["diagnostics"] as List<*>
            // Should have low or no errors for valid smali
            
            // Test 9: Shutdown
            stdin.write(gson.toJson(mapOf("command" to "shutdown")))
            stdin.newLine()
            stdin.flush()
            
            // Wait for process to exit
            val exited = process.waitFor(3, TimeUnit.SECONDS)
            assertTrue(exited, "Daemon should exit after shutdown command")
            
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }
    
    @Test
    fun `test error handling - no index loaded`() {
        val process = startDaemonProcess()
        
        try {
            val stdin = BufferedWriter(OutputStreamWriter(process.outputStream))
            val stdout = BufferedReader(InputStreamReader(process.inputStream))
            val stderr = BufferedReader(InputStreamReader(process.errorStream))
            
            // Wait for ready
            thread { stderr.forEachLine { } }
            Thread.sleep(2000)
            
            // Try to query without indexing first
            val response = sendCommand(
                stdin, stdout,
                "find-definition",
                mapOf("symbol" to "Lcom/example/Test;")
            )
            
            assertEquals(false, response["success"])
            assertTrue(response["error"].toString().contains("No index loaded"))
            
        } finally {
            process.destroyForcibly()
        }
    }
    
    @Test
    fun `test error handling - invalid directory`() {
        val process = startDaemonProcess()
        
        try {
            val stdin = BufferedWriter(OutputStreamWriter(process.outputStream))
            val stdout = BufferedReader(InputStreamReader(process.inputStream))
            val stderr = BufferedReader(InputStreamReader(process.errorStream))
            
            // Wait for ready
            thread { stderr.forEachLine { } }
            Thread.sleep(2000)
            
            // Try to index non-existent directory
            val response = sendCommand(
                stdin, stdout,
                "index",
                mapOf("directory" to "/nonexistent/path/12345")
            )
            
            assertEquals(false, response["success"])
            assertTrue(response["error"].toString().contains("not found"))
            
        } finally {
            process.destroyForcibly()
        }
    }
    
    @Test
    fun `test error handling - missing arguments`() {
        val process = startDaemonProcess()
        
        try {
            val stdin = BufferedWriter(OutputStreamWriter(process.outputStream))
            val stdout = BufferedReader(InputStreamReader(process.inputStream))
            val stderr = BufferedReader(InputStreamReader(process.errorStream))
            
            // Wait for ready
            thread { stderr.forEachLine { } }
            Thread.sleep(2000)
            
            // Index first (required before any query)
            sendCommand(
                stdin, stdout,
                "index",
                mapOf("directory" to testProjectDir.absolutePath)
            )
            
            // Try command with missing args
            val response = sendCommand(
                stdin, stdout,
                "find-definition",
                emptyMap() // Missing 'symbol' argument
            )
            
            assertEquals(false, response["success"])
            assertTrue(response["error"].toString().contains("Missing"))
            
        } finally {
            process.destroyForcibly()
        }
    }
    
    @Test
    fun `test URI normalization - file with single slash`() {
        val process = startDaemonProcess()
        
        try {
            val stdin = BufferedWriter(OutputStreamWriter(process.outputStream))
            val stdout = BufferedReader(InputStreamReader(process.inputStream))
            val stderr = BufferedReader(InputStreamReader(process.errorStream))
            
            // Wait for ready
            thread { stderr.forEachLine { } }
            Thread.sleep(2000)
            
            // Index first
            sendCommand(
                stdin, stdout,
                "index",
                mapOf("directory" to testProjectDir.absolutePath)
            )
            
            // Try with file:/ (single slash) - should be auto-normalized to file:///
            val singleSlashUri = "file:${smaliFile.absolutePath}"
            val response = sendCommand(
                stdin, stdout,
                "document-symbols",
                mapOf("uri" to singleSlashUri)
            )
            
            // Should work because WorkspaceIndex normalizes URIs
            assertEquals(true, response["success"])
            
        } finally {
            process.destroyForcibly()
        }
    }
    
    @Test
    fun `test unknown command`() {
        val process = startDaemonProcess()
        
        try {
            val stdin = BufferedWriter(OutputStreamWriter(process.outputStream))
            val stdout = BufferedReader(InputStreamReader(process.inputStream))
            val stderr = BufferedReader(InputStreamReader(process.errorStream))
            
            // Wait for ready
            thread { stderr.forEachLine { } }
            Thread.sleep(2000)
            
            val response = sendCommand(
                stdin, stdout,
                "invalid-command",
                emptyMap()
            )
            
            assertEquals(false, response["success"])
            assertTrue(response["error"].toString().contains("Unknown command"))
            
        } finally {
            process.destroyForcibly()
        }
    }
    
    @Test
    fun `test diagnostics with invalid smali`() {
        val process = startDaemonProcess()
        
        try {
            val stdin = BufferedWriter(OutputStreamWriter(process.outputStream))
            val stdout = BufferedReader(InputStreamReader(process.inputStream))
            val stderr = BufferedReader(InputStreamReader(process.errorStream))
            
            // Wait for ready
            thread { stderr.forEachLine { } }
            Thread.sleep(2000)
            
            // Index first
            sendCommand(
                stdin, stdout,
                "index",
                mapOf("directory" to testProjectDir.absolutePath)
            )
            
            // Test with invalid smali (undefined class reference)
            val invalidSmali = """
                .class public Lcom/example/Invalid;
                .super Ljava/lang/Object;
                
                .method public test()V
                    invoke-virtual {p0}, Lcom/nonexistent/FakeClass;->fakeMethod()V
                    return-void
                .end method
            """.trimIndent()
            
            val response = sendCommand(
                stdin, stdout,
                "diagnostics",
                mapOf(
                    "uri" to "file:///tmp/Invalid.smali",
                    "content" to invalidSmali
                )
            )
            
            assertEquals(true, response["success"])
            @Suppress("UNCHECKED_CAST")
            val diagnostics = response["diagnostics"] as List<*>
            // Should detect the undefined class
            assertTrue(diagnostics.isNotEmpty())
            
        } finally {
            process.destroyForcibly()
        }
    }
    
    /**
     * Helper to start daemon process.
     */
    private fun startDaemonProcess(): Process {
        // Build the jar if needed
        val jarFile = File("build/libs").listFiles()
            ?.firstOrNull { it.name.startsWith("smali-lsp") && it.name.endsWith("-all.jar") }
        assumeTrue(jarFile != null, "LSP jar not found — run './gradlew shadowJar' first, skipping daemon tests")

        return ProcessBuilder(
            "java", "-jar", jarFile!!.absolutePath, "--daemon"
        ).start()
    }
}
