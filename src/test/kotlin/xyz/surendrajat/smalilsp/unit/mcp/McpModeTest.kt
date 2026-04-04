package xyz.surendrajat.smalilsp.unit.mcp

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
    fun `test tools list returns all 11 tools`() {
        val process = startMcpProcess()

        try {
            val (stdin, stdout) = initializeSession(process)

            val response = sendRequest(stdin, stdout, 10, "tools/list")
            assertJsonRpcSuccess(response, 10)

            val result = response.getAsJsonObject("result")
            val tools = result.getAsJsonArray("tools")

            val toolNames = tools.map { it.asJsonObject.get("name").asString }.toSet()
            assertEquals(11, toolNames.size, "Expected 11 tools, got: $toolNames")

            val expectedTools = setOf(
                "smali_index", "smali_find_definition", "smali_search_symbols",
                "smali_get_stats", "smali_find_references", "smali_hover",
                "smali_diagnostics", "smali_document_symbols",
                "smali_search_strings", "smali_call_graph", "smali_xref_summary"
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

    @Test
    fun `test document_symbols includes line ranges`() {
        val process = startMcpProcess()
        try {
            val (stdin, stdout) = initializeSession(process)

            callTool(stdin, stdout, 80, "smali_index", mapOf("directory" to testProjectDir.absolutePath))

            val response = callTool(stdin, stdout, 81, "smali_document_symbols", mapOf("uri" to smaliFileUri))
            assertJsonRpcSuccess(response, 81)
            assertFalse(isToolError(response))

            val result = JsonParser.parseString(getToolResultText(response)).asJsonObject
            val symbols = result.getAsJsonArray("symbols")

            for (sym in symbols) {
                val symObj = sym.asJsonObject
                assertTrue(symObj.has("range"), "Symbol '${symObj.get("kind")}' missing 'range'")
                val range = symObj.getAsJsonObject("range")
                assertTrue(range.has("start"), "range missing 'start'")
                assertTrue(range.has("end"), "range missing 'end'")
                assertTrue(range.getAsJsonObject("start").has("line"), "start missing 'line'")
                assertTrue(range.getAsJsonObject("end").has("line"), "end missing 'line'")
            }
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `test search_symbols matches package path`() {
        val process = startMcpProcess()
        try {
            val (stdin, stdout) = initializeSession(process)

            callTool(stdin, stdout, 90, "smali_index", mapOf("directory" to testProjectDir.absolutePath))

            // Search by package segment "example" — should match "Lcom/example/test/MainActivity;"
            val response = callTool(stdin, stdout, 91, "smali_search_symbols", mapOf("pattern" to "example"))
            assertJsonRpcSuccess(response, 91)
            assertFalse(isToolError(response))

            val result = JsonParser.parseString(getToolResultText(response)).asJsonObject
            assertTrue(result.get("count").asInt > 0,
                "Package path search for 'example' should match Lcom/example/test/MainActivity;")
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `test re-index does not corrupt previous results`() {
        val process = startMcpProcess()
        try {
            val (stdin, stdout) = initializeSession(process)

            // First index
            val r1 = callTool(stdin, stdout, 100, "smali_index", mapOf("directory" to testProjectDir.absolutePath))
            assertFalse(isToolError(r1))
            val stats1 = JsonParser.parseString(
                getToolResultText(callTool(stdin, stdout, 101, "smali_get_stats"))
            ).asJsonObject
            val classes1 = stats1.get("classes").asInt

            // Re-index the same directory
            val r2 = callTool(stdin, stdout, 102, "smali_index", mapOf("directory" to testProjectDir.absolutePath))
            assertFalse(isToolError(r2))
            val stats2 = JsonParser.parseString(
                getToolResultText(callTool(stdin, stdout, 103, "smali_get_stats"))
            ).asJsonObject
            val classes2 = stats2.get("classes").asInt

            assertEquals(classes1, classes2, "Re-index should produce same class count (OOM regression check)")
        } finally {
            process.destroyForcibly()
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // End-to-end tests added to catch real-world regressions early
    // ────────────────────────────────────────────────────────────────────────

    /** Generate N synthetic-but-valid smali files in a temp directory. */
    private fun createSmaliProject(root: File, count: Int): List<File> {
        val dir = File(root, "smali/com/e2e/test")
        dir.mkdirs()
        return (1..count).map { i ->
            val f = File(dir, "Cls$i.smali")
            f.writeText("""
                .class public Lcom/e2e/test/Cls$i;
                .super Ljava/lang/Object;
                .field private value$i:I

                .method public constructor <init>()V
                    .registers 2
                    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                    const/4 v0, 0x0
                    iput v0, p0, Lcom/e2e/test/Cls$i;->value$i:I
                    return-void
                .end method

                .method public getValue$i()I
                    .registers 2
                    iget v0, p0, Lcom/e2e/test/Cls$i;->value$i:I
                    return v0
                .end method
            """.trimIndent())
            f
        }
    }

    @Test
    fun `test indexing 200 files completes within 60 seconds`() {
        val root = Files.createTempDirectory("mcp-e2e-large").toFile()
        createSmaliProject(root, 200)

        val process = startMcpProcess()
        try {
            val (stdin, stdout) = initializeSession(process)

            val start = System.currentTimeMillis()
            val response = callTool(stdin, stdout, 200, "smali_index", mapOf("directory" to root.absolutePath))
            val elapsed = System.currentTimeMillis() - start

            assertJsonRpcSuccess(response, 200)
            assertFalse(isToolError(response), "Indexing 200 files must not fail: ${getToolResultText(response)}")

            assertTrue(elapsed < 60_000, "Indexing 200 files took ${elapsed}ms — performance regression?")

            val statsText = getToolResultText(callTool(stdin, stdout, 201, "smali_get_stats"))
            val stats = JsonParser.parseString(statsText).asJsonObject
            assertEquals(200, stats.get("classes").asInt, "Expected exactly 200 classes indexed")
            // 200 classes × 2 methods each
            assertTrue(stats.get("methods").asInt >= 400, "Expected >= 400 methods")
        } finally {
            process.destroyForcibly()
            root.deleteRecursively()
        }
    }

    @Test
    fun `test indexing directory with a malformed smali file succeeds for valid files`() {
        val root = Files.createTempDirectory("mcp-e2e-errors").toFile()
        val smaliDir = File(root, "smali/com/test")
        smaliDir.mkdirs()

        // A perfectly valid class
        File(smaliDir, "GoodClass.smali").writeText("""
            .class public Lcom/test/GoodClass;
            .super Ljava/lang/Object;
            .method public hello()V
                .registers 1
                return-void
            .end method
        """.trimIndent())

        // Deliberately broken smali — missing directives, random garbage
        File(smaliDir, "BadClass.smali").writeText("this is not smali at all $$$ ???")

        val process = startMcpProcess()
        try {
            val (stdin, stdout) = initializeSession(process)

            val response = callTool(stdin, stdout, 210, "smali_index", mapOf("directory" to root.absolutePath))
            assertJsonRpcSuccess(response, 210)
            assertFalse(isToolError(response), "Indexing must not fail because of one bad file")

            // GoodClass must be findable despite the bad file
            val defResponse = callTool(stdin, stdout, 211, "smali_find_definition",
                mapOf("symbol" to "Lcom/test/GoodClass;"))
            assertJsonRpcSuccess(defResponse, 211)
            assertFalse(isToolError(defResponse))
            val def = JsonParser.parseString(getToolResultText(defResponse)).asJsonObject
            assertEquals(1, def.get("count").asInt, "GoodClass must be found even after bad file next to it")
        } finally {
            process.destroyForcibly()
            root.deleteRecursively()
        }
    }

    @Test
    fun `test indexing empty directory returns zero stats`() {
        val root = Files.createTempDirectory("mcp-e2e-empty").toFile()
        File(root, "smali").mkdirs()

        val process = startMcpProcess()
        try {
            val (stdin, stdout) = initializeSession(process)

            val response = callTool(stdin, stdout, 220, "smali_index", mapOf("directory" to root.absolutePath))
            assertJsonRpcSuccess(response, 220)
            assertFalse(isToolError(response))

            val statsText = getToolResultText(callTool(stdin, stdout, 221, "smali_get_stats"))
            val stats = JsonParser.parseString(statsText).asJsonObject
            assertEquals(0, stats.get("classes").asInt, "Empty dir must produce zero classes")
            assertEquals(0, stats.get("methods").asInt)
            assertEquals(0, stats.get("fields").asInt)
        } finally {
            process.destroyForcibly()
            root.deleteRecursively()
        }
    }

    @Test
    fun `test find definition returns empty results for unknown class`() {
        val process = startMcpProcess()
        try {
            val (stdin, stdout) = initializeSession(process)

            callTool(stdin, stdout, 230, "smali_index", mapOf("directory" to testProjectDir.absolutePath))

            val response = callTool(stdin, stdout, 231, "smali_find_definition",
                mapOf("symbol" to "Lcom/totally/nonexistent/Class;"))
            assertJsonRpcSuccess(response, 231)
            assertFalse(isToolError(response), "Unknown class must return empty results, not an error")
            val result = JsonParser.parseString(getToolResultText(response)).asJsonObject
            assertEquals(0, result.get("count").asInt)
            assertEquals(0, result.getAsJsonArray("results").size())
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `test search with empty pattern returns symbols`() {
        val process = startMcpProcess()
        try {
            val (stdin, stdout) = initializeSession(process)

            callTool(stdin, stdout, 240, "smali_index", mapOf("directory" to testProjectDir.absolutePath))

            val response = callTool(stdin, stdout, 241, "smali_search_symbols", mapOf("pattern" to ""))
            assertJsonRpcSuccess(response, 241)
            assertFalse(isToolError(response))
            val result = JsonParser.parseString(getToolResultText(response)).asJsonObject
            // At minimum the one class should be present
            assertTrue(result.get("count").asInt >= 1, "Empty pattern search must return at least 1 symbol")
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `test 20 sequential tool calls remain fast after indexing`() {
        val process = startMcpProcess()
        try {
            val (stdin, stdout) = initializeSession(process)

            callTool(stdin, stdout, 250, "smali_index", mapOf("directory" to testProjectDir.absolutePath))

            var id = 251
            repeat(20) {
                val start = System.currentTimeMillis()
                val resp = callTool(stdin, stdout, id++, "smali_search_symbols", mapOf("pattern" to "main"))
                val elapsed = System.currentTimeMillis() - start
                assertJsonRpcSuccess(resp, id - 1)
                assertFalse(isToolError(resp))
                assertTrue(elapsed < 5_000, "Tool call $it took ${elapsed}ms — server may be stuck")
            }
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun `test three consecutive re-indexes give identical stats`() {
        val root = Files.createTempDirectory("mcp-e2e-reindex").toFile()
        createSmaliProject(root, 50)

        val process = startMcpProcess()
        try {
            val (stdin, stdout) = initializeSession(process)

            var id = 260
            val counts = (1..3).map {
                callTool(stdin, stdout, id++, "smali_index", mapOf("directory" to root.absolutePath))
                val statsText = getToolResultText(callTool(stdin, stdout, id++, "smali_get_stats"))
                JsonParser.parseString(statsText).asJsonObject.get("classes").asInt
            }

            assertEquals(1, counts.distinct().size,
                "Re-indexing same dir 3× must give identical class count: $counts")
            assertEquals(50, counts[0], "Expected 50 classes after each index")
        } finally {
            process.destroyForcibly()
            root.deleteRecursively()
        }
    }

    @Test
    fun `test search finds classes across package hierarchy`() {
        val root = Files.createTempDirectory("mcp-e2e-search").toFile()
        // Deep package structure
        val deep = File(root, "smali/com/example/billing/internal")
        deep.mkdirs()
        File(deep, "BillingManager.smali").writeText("""
            .class public Lcom/example/billing/internal/BillingManager;
            .super Ljava/lang/Object;
            .method public purchase()V
                .registers 1
                return-void
            .end method
        """.trimIndent())

        val process = startMcpProcess()
        try {
            val (stdin, stdout) = initializeSession(process)

            callTool(stdin, stdout, 270, "smali_index", mapOf("directory" to root.absolutePath))

            // Search by simple class name
            val byName = JsonParser.parseString(
                getToolResultText(callTool(stdin, stdout, 271, "smali_search_symbols", mapOf("pattern" to "BillingManager")))
            ).asJsonObject
            assertTrue(byName.get("count").asInt >= 1, "Search by class name must find BillingManager")

            // Search by package segment
            val byPkg = JsonParser.parseString(
                getToolResultText(callTool(stdin, stdout, 272, "smali_search_symbols", mapOf("pattern" to "billing")))
            ).asJsonObject
            assertTrue(byPkg.get("count").asInt >= 1, "Search by package segment 'billing' must find BillingManager")

            // Search by nested package segment
            val byNested = JsonParser.parseString(
                getToolResultText(callTool(stdin, stdout, 273, "smali_search_symbols", mapOf("pattern" to "internal")))
            ).asJsonObject
            assertTrue(byNested.get("count").asInt >= 1, "Search by 'internal' must find class in internal package")
        } finally {
            process.destroyForcibly()
            root.deleteRecursively()
        }
    }

    @Test
    fun `test document_symbols returns method and field details`() {
        val process = startMcpProcess()
        try {
            val (stdin, stdout) = initializeSession(process)

            callTool(stdin, stdout, 280, "smali_index", mapOf("directory" to testProjectDir.absolutePath))

            val symResponse = callTool(stdin, stdout, 281, "smali_document_symbols", mapOf("uri" to smaliFileUri))
            assertJsonRpcSuccess(symResponse, 281)
            assertFalse(isToolError(symResponse))

            val result = JsonParser.parseString(getToolResultText(symResponse)).asJsonObject
            val symbols = result.getAsJsonArray("symbols")

            val kinds = symbols.map { it.asJsonObject.get("kind").asString }.toSet()
            assertTrue("class" in kinds, "document_symbols must include class symbol")
            assertTrue("method" in kinds, "document_symbols must include method symbols")
            assertTrue("field" in kinds, "document_symbols must include field symbols")

            // Every symbol must have a non-zero line range (end > start or at minimum present)
            for (sym in symbols) {
                val obj = sym.asJsonObject
                val range = obj.getAsJsonObject("range")
                val startLine = range.getAsJsonObject("start").get("line").asInt
                assertTrue(startLine >= 0, "Symbol '${obj.get("kind")}' has negative start line")
            }
        } finally {
            process.destroyForcibly()
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
