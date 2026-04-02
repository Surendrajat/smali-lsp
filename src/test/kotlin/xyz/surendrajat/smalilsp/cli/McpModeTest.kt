package xyz.surendrajat.smalilsp.cli

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.*
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Integration tests for McpMode (MCP server over JSON-RPC 2.0 stdio).
 *
 * Spawns the JAR with --mcp and tests the full MCP lifecycle:
 * - Initialization handshake (initialize -> initialized)
 * - Tool discovery (tools/list)
 * - Tool execution (tools/call) for all 8 tools
 * - Error handling (unknown tool, missing args, no index)
 * - Protocol compliance (JSON-RPC 2.0 format)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpModeTest {

    private val gson = Gson()

    private lateinit var testProjectDir: File
    private lateinit var smaliFile: File
    private lateinit var smaliFileUri: String

    @BeforeEach
    fun setup() {
        val tempDir = Files.createTempDirectory("mcp-test")
        testProjectDir = tempDir.resolve("test-project").toFile()
        testProjectDir.mkdirs()

        val smaliDir = File(testProjectDir, "smali/com/example/test")
        smaliDir.mkdirs()

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

        smaliFileUri = smaliFile.toURI().toString()
    }

    // --- JSON-RPC helpers ---

    private fun sendRequest(
        stdin: BufferedWriter,
        stdout: BufferedReader,
        id: Any,
        method: String,
        params: Any? = null
    ): JsonObject {
        val request = mutableMapOf<String, Any?>(
            "jsonrpc" to "2.0",
            "id" to id,
            "method" to method
        )
        if (params != null) {
            request["params"] = params
        }

        val json = gson.toJson(request)
        stdin.write(json)
        stdin.newLine()
        stdin.flush()

        val response = stdout.readLine()
        assertNotNull(response, "Expected response but got null (server may have crashed)")
        return JsonParser.parseString(response).asJsonObject
    }

    private fun sendNotification(stdin: BufferedWriter, method: String) {
        val notification = mapOf("jsonrpc" to "2.0", "method" to method)
        stdin.write(gson.toJson(notification))
        stdin.newLine()
        stdin.flush()
    }

    private fun callTool(
        stdin: BufferedWriter,
        stdout: BufferedReader,
        id: Any,
        toolName: String,
        arguments: Map<String, Any?> = emptyMap()
    ): JsonObject {
        return sendRequest(stdin, stdout, id, "tools/call", mapOf(
            "name" to toolName,
            "arguments" to arguments
        ))
    }

    private fun assertJsonRpcSuccess(response: JsonObject, expectedId: Any) {
        assertEquals("2.0", response.get("jsonrpc").asString, "Must be JSON-RPC 2.0")
        assertNotNull(response.get("result"), "Expected result, got: $response")
        assertNull(response.get("error"), "Unexpected error: ${response.get("error")}")
        when (expectedId) {
            is Int -> assertEquals(expectedId, response.get("id").asInt)
            is String -> assertEquals(expectedId, response.get("id").asString)
        }
    }

    private fun assertJsonRpcError(response: JsonObject) {
        assertEquals("2.0", response.get("jsonrpc").asString)
        assertNotNull(response.get("error"), "Expected error, got: $response")
    }

    private fun getToolResultText(response: JsonObject): String {
        val result = response.getAsJsonObject("result")
        val content = result.getAsJsonArray("content")
        return content[0].asJsonObject.get("text").asString
    }

    private fun isToolError(response: JsonObject): Boolean {
        val result = response.getAsJsonObject("result") ?: return false
        return result.get("isError")?.asBoolean == true
    }

    // --- Tests ---

    @Test
    fun `test MCP initialization handshake`() {
        val process = startMcpProcess()

        try {
            val stdin = BufferedWriter(OutputStreamWriter(process.outputStream))
            val stdout = BufferedReader(InputStreamReader(process.inputStream))
            drainStderr(process)

            // Step 1: Send initialize request
            val initResponse = sendRequest(stdin, stdout, 1, "initialize", mapOf(
                "protocolVersion" to "2025-03-26",
                "capabilities" to emptyMap<String, Any>(),
                "clientInfo" to mapOf("name" to "test-client", "version" to "1.0.0")
            ))

            assertJsonRpcSuccess(initResponse, 1)

            val result = initResponse.getAsJsonObject("result")
            assertEquals("2025-03-26", result.get("protocolVersion").asString)

            val capabilities = result.getAsJsonObject("capabilities")
            assertNotNull(capabilities.get("tools"), "Server must declare tools capability")

            val serverInfo = result.getAsJsonObject("serverInfo")
            assertEquals("smali-lsp", serverInfo.get("name").asString)

            // Step 2: Send initialized notification (no response expected)
            sendNotification(stdin, "notifications/initialized")

            // Step 3: Verify server still responds after initialization
            val listResponse = sendRequest(stdin, stdout, 2, "tools/list")
            assertJsonRpcSuccess(listResponse, 2)

        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `test tools list returns all 8 tools`() {
        val process = startMcpProcess()

        try {
            val (stdin, stdout) = initializeSession(process)

            val response = sendRequest(stdin, stdout, 10, "tools/list")
            assertJsonRpcSuccess(response, 10)

            val result = response.getAsJsonObject("result")
            val tools = result.getAsJsonArray("tools")

            val toolNames = tools.map { it.asJsonObject.get("name").asString }.toSet()
            assertEquals(8, toolNames.size, "Expected 8 tools, got: $toolNames")

            val expectedTools = setOf(
                "smali_index", "smali_find_definition", "smali_search_symbols",
                "smali_get_stats", "smali_find_references", "smali_hover",
                "smali_diagnostics", "smali_document_symbols"
            )
            assertEquals(expectedTools, toolNames)

            // Verify each tool has required MCP fields
            for (tool in tools) {
                val toolObj = tool.asJsonObject
                assertNotNull(toolObj.get("name"), "Tool missing name")
                assertNotNull(toolObj.get("description"), "Tool missing description")
                assertNotNull(toolObj.get("inputSchema"), "Tool missing inputSchema")

                val schema = toolObj.getAsJsonObject("inputSchema")
                assertEquals("object", schema.get("type").asString, "inputSchema type must be 'object'")
            }

        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `test full tool lifecycle - index, query, search`() {
        val process = startMcpProcess()

        try {
            val (stdin, stdout) = initializeSession(process)

            // 1. Index
            val indexResponse = callTool(stdin, stdout, 20, "smali_index", mapOf(
                "directory" to testProjectDir.absolutePath
            ))
            assertJsonRpcSuccess(indexResponse, 20)
            assertFalse(isToolError(indexResponse))
            val indexText = getToolResultText(indexResponse)
            assertTrue(indexText.contains("Successfully indexed"))
            assertTrue(indexText.contains("Classes:"))

            // 2. Get stats
            val statsResponse = callTool(stdin, stdout, 21, "smali_get_stats")
            assertJsonRpcSuccess(statsResponse, 21)
            assertFalse(isToolError(statsResponse))
            val statsText = getToolResultText(statsResponse)
            val stats = JsonParser.parseString(statsText).asJsonObject
            assertEquals(1, stats.get("classes").asInt)
            assertTrue(stats.get("methods").asInt >= 3) // constructor, main, increment
            assertTrue(stats.get("fields").asInt >= 2) // TAG, counter

            // 3. Find definition
            val defResponse = callTool(stdin, stdout, 22, "smali_find_definition", mapOf(
                "symbol" to "Lcom/example/test/MainActivity;"
            ))
            assertJsonRpcSuccess(defResponse, 22)
            assertFalse(isToolError(defResponse))
            val defText = getToolResultText(defResponse)
            val defResult = JsonParser.parseString(defText).asJsonObject
            assertEquals(1, defResult.get("count").asInt)
            val results = defResult.getAsJsonArray("results")
            assertEquals("class", results[0].asJsonObject.get("type").asString)

            // 4. Search symbols
            val searchResponse = callTool(stdin, stdout, 23, "smali_search_symbols", mapOf(
                "pattern" to "main"
            ))
            assertJsonRpcSuccess(searchResponse, 23)
            assertFalse(isToolError(searchResponse))
            val searchText = getToolResultText(searchResponse)
            val searchResult = JsonParser.parseString(searchText).asJsonObject
            assertTrue(searchResult.get("count").asInt > 0)

            // 5. Document symbols
            val symbolsResponse = callTool(stdin, stdout, 24, "smali_document_symbols", mapOf(
                "uri" to smaliFileUri
            ))
            assertJsonRpcSuccess(symbolsResponse, 24)
            assertFalse(isToolError(symbolsResponse))
            val symbolsText = getToolResultText(symbolsResponse)
            val symbolsResult = JsonParser.parseString(symbolsText).asJsonObject
            assertTrue(symbolsResult.get("count").asInt >= 4)

            // 6. Hover
            val hoverResponse = callTool(stdin, stdout, 25, "smali_hover", mapOf(
                "uri" to smaliFileUri,
                "line" to 17,
                "character" to 50
            ))
            assertJsonRpcSuccess(hoverResponse, 25)
            assertFalse(isToolError(hoverResponse))

            // 7. Find references
            val refsResponse = callTool(stdin, stdout, 26, "smali_find_references", mapOf(
                "uri" to smaliFileUri,
                "line" to 17,
                "character" to 50
            ))
            assertJsonRpcSuccess(refsResponse, 26)
            assertFalse(isToolError(refsResponse))

            // 8. Diagnostics
            val diagResponse = callTool(stdin, stdout, 27, "smali_diagnostics", mapOf(
                "uri" to smaliFileUri,
                "content" to smaliFile.readText()
            ))
            assertJsonRpcSuccess(diagResponse, 27)
            assertFalse(isToolError(diagResponse))

        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `test tool error - no index loaded`() {
        val process = startMcpProcess()

        try {
            val (stdin, stdout) = initializeSession(process)

            val response = callTool(stdin, stdout, 30, "smali_find_definition", mapOf(
                "symbol" to "Lcom/example/Test;"
            ))

            // Tool-level error is returned as JSON-RPC success with isError=true
            assertJsonRpcSuccess(response, 30)
            assertTrue(isToolError(response))
            assertTrue(getToolResultText(response).contains("No index loaded"))

        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `test tool error - invalid directory`() {
        val process = startMcpProcess()

        try {
            val (stdin, stdout) = initializeSession(process)

            val response = callTool(stdin, stdout, 31, "smali_index", mapOf(
                "directory" to "/nonexistent/path/12345"
            ))

            assertJsonRpcSuccess(response, 31)
            assertTrue(isToolError(response))
            assertTrue(getToolResultText(response).contains("not found"))

        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `test tool error - missing arguments`() {
        val process = startMcpProcess()

        try {
            val (stdin, stdout) = initializeSession(process)

            // Index first
            callTool(stdin, stdout, 40, "smali_index", mapOf(
                "directory" to testProjectDir.absolutePath
            ))

            // Call find_definition without required 'symbol' arg
            val response = callTool(stdin, stdout, 41, "smali_find_definition")

            assertJsonRpcSuccess(response, 41)
            assertTrue(isToolError(response))
            assertTrue(getToolResultText(response).contains("Missing"))

        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `test diagnostics with invalid smali detects errors`() {
        val process = startMcpProcess()

        try {
            val (stdin, stdout) = initializeSession(process)

            callTool(stdin, stdout, 70, "smali_index", mapOf(
                "directory" to testProjectDir.absolutePath
            ))

            val invalidSmali = """
                .class public Lcom/example/Invalid;
                .super Ljava/lang/Object;

                .method public test()V
                    invoke-virtual {p0}, Lcom/nonexistent/FakeClass;->fakeMethod()V
                    return-void
                .end method
            """.trimIndent()

            val response = callTool(stdin, stdout, 71, "smali_diagnostics", mapOf(
                "uri" to "file:///tmp/Invalid.smali",
                "content" to invalidSmali
            ))

            assertJsonRpcSuccess(response, 71)
            assertFalse(isToolError(response))
            val text = getToolResultText(response)
            val result = JsonParser.parseString(text).asJsonObject
            val diagnostics = result.getAsJsonArray("diagnostics")
            assertTrue(diagnostics.size() > 0, "Should detect undefined class reference")

        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `test graceful shutdown on stdin close`() {
        val process = startMcpProcess()

        try {
            val (stdin, _) = initializeSession(process)

            // Close stdin - server should exit gracefully
            stdin.close()

            val exited = process.waitFor(10, TimeUnit.SECONDS)
            assertTrue(exited, "Server should exit when stdin is closed")

        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }

    // --- Helpers ---

    private fun startMcpProcess(): Process {
        val jarFile = File("build/libs").listFiles()
            ?.filter { it.name.startsWith("smali-lsp") && it.name.endsWith("-all.jar") }
            ?.maxByOrNull { it.lastModified() }
        assumeTrue(jarFile != null, "LSP jar not found — run './gradlew shadowJar' first, skipping MCP tests")

        return ProcessBuilder(
            "java", "-jar", jarFile!!.absolutePath, "--mcp"
        ).start()
    }

    private fun drainStderr(process: Process) {
        thread(isDaemon = true) {
            process.errorStream.bufferedReader().forEachLine { /* discard */ }
        }
    }

    private fun initializeSession(process: Process): Pair<BufferedWriter, BufferedReader> {
        val stdin = BufferedWriter(OutputStreamWriter(process.outputStream))
        val stdout = BufferedReader(InputStreamReader(process.inputStream))
        drainStderr(process)

        val initResponse = sendRequest(stdin, stdout, 0, "initialize", mapOf(
            "protocolVersion" to "2025-03-26",
            "capabilities" to emptyMap<String, Any>(),
            "clientInfo" to mapOf("name" to "test-client", "version" to "1.0.0")
        ))
        assertJsonRpcSuccess(initResponse, 0)

        sendNotification(stdin, "notifications/initialized")

        return Pair(stdin, stdout)
    }
}
