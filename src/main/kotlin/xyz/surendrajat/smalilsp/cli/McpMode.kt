package xyz.surendrajat.smalilsp.cli

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.eclipse.lsp4j.Position
import org.slf4j.LoggerFactory
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.indexer.WorkspaceScanner
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.providers.DiagnosticProvider
import xyz.surendrajat.smalilsp.providers.HoverProvider
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import xyz.surendrajat.smalilsp.providers.WorkspaceSymbolProvider
import java.io.File

/**
 * MCP (Model Context Protocol) server mode for Smali LSP.
 *
 * Uses the official MCP Kotlin SDK to expose smali analysis tools
 * to AI agents (Claude, Cursor, etc.) over stdio.
 *
 * Usage:
 *   java -jar smali-lsp-all.jar --mcp
 *
 * MCP config (claude_desktop_config.json / cursor settings):
 *   {
 *     "mcpServers": {
 *       "smali-lsp": {
 *         "command": "java",
 *         "args": ["-jar", "/path/to/smali-lsp-all.jar", "--mcp"]
 *       }
 *     }
 *   }
 */
class McpMode {

    private val logger = LoggerFactory.getLogger(McpMode::class.java)
    private val gson = com.google.gson.Gson()

    // In-memory index (persists across queries)
    private var index: WorkspaceIndex? = null
    private var indexedDirectory: String? = null
    private val parser = SmaliParser()

    // LSP providers (initialized after indexing)
    private var definitionProvider: DefinitionProvider? = null
    private var hoverProvider: HoverProvider? = null
    private var referenceProvider: ReferenceProvider? = null
    private var diagnosticProvider: DiagnosticProvider? = null
    private var workspaceSymbolProvider: WorkspaceSymbolProvider? = null

    fun run() {
        val server = Server(
            Implementation(name = "smali-lsp", version = "1.0.0"),
            ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))
            )
        )

        registerTools(server)

        val transport = StdioServerTransport(
            System.`in`.asSource().buffered(),
            System.out.asSink().buffered()
        )

        runBlocking {
            val session = server.createSession(transport)
            logger.info("MCP server started")
            val done = Job()
            session.onClose { done.complete() }
            done.join()
        }
    }

    private fun registerTools(server: Server) {
        server.addTool(
            name = "smali_index",
            description = "Index smali files in an APKTool-decompiled directory. Call this first before using any other tool. " +
                    "Keeps the index in memory for fast subsequent queries (<1s).",
            inputSchema = toolSchema(
                properties = buildJsonObject {
                    putJsonObject("directory") {
                        put("type", "string")
                        put("description", "Path to APKTool project directory containing smali files")
                    }
                },
                required = listOf("directory")
            )
        ) { request ->
            val directory = request.arguments?.get("directory")?.jsonPrimitive?.content
                ?: return@addTool toolError("Missing required argument: 'directory'")

            val dir = File(directory)
            if (!dir.exists() || !dir.isDirectory) {
                return@addTool toolError("Directory not found: $directory")
            }

            logger.info("Indexing: ${dir.absolutePath}")
            val startTime = System.currentTimeMillis()

            // Drop all references to the old index BEFORE building the new one.
            // For large projects (100K+ files) both old and new indexes would
            // otherwise coexist in heap during the scan, causing OOM.
            // Note: no System.gc() here — forcing a full GC before a 100K-file scan
            // pegs all CPUs with GC threads and makes the problem worse, not better.
            // Let the JVM's G1GC handle collection incrementally during the scan.
            index = null
            definitionProvider = null
            hoverProvider = null
            referenceProvider = null
            diagnosticProvider = null
            workspaceSymbolProvider = null

            val newIndex = WorkspaceIndex()
            val scanner = WorkspaceScanner(newIndex)
            val result = scanner.scanDirectory(dir) { _, _ -> }

            index = newIndex
            definitionProvider = DefinitionProvider(newIndex)
            hoverProvider = HoverProvider(newIndex)
            referenceProvider = ReferenceProvider(newIndex)
            diagnosticProvider = DiagnosticProvider(parser, newIndex)
            workspaceSymbolProvider = WorkspaceSymbolProvider(newIndex)

            indexedDirectory = dir.absolutePath
            val duration = System.currentTimeMillis() - startTime
            val stats = index!!.getStats()

            toolResult(
                "Successfully indexed $directory\n" +
                        "Files: ${result.filesSucceeded}\n" +
                        "Time: ${duration}ms\n" +
                        "Classes: ${stats.classes}, Methods: ${stats.methods}, Fields: ${stats.fields}\n\n" +
                        "Index is now in memory. Subsequent queries will be fast (<1s)."
            )
        }

        server.addTool(
            name = "smali_find_definition",
            description = "Find the definition of a class. Returns class info including superclass, interfaces, methods, and fields.",
            inputSchema = toolSchema(
                properties = buildJsonObject {
                    putJsonObject("symbol") {
                        put("type", "string")
                        put("description", "Fully qualified class name (e.g., 'Lcom/example/MainActivity;')")
                    }
                },
                required = listOf("symbol")
            )
        ) { request ->
            requireIndex() ?: return@addTool toolError("No index loaded. Call smali_index first.")

            val symbol = request.arguments?.get("symbol")?.jsonPrimitive?.content
                ?: return@addTool toolError("Missing required argument: 'symbol'")

            val classFile = index!!.findClass(symbol)
            if (classFile != null) {
                val result = mapOf(
                    "type" to "class",
                    "name" to classFile.classDefinition.name,
                    "uri" to classFile.uri,
                    "superClass" to classFile.classDefinition.superClass,
                    "interfaces" to classFile.classDefinition.interfaces,
                    "methods" to classFile.methods.size,
                    "fields" to classFile.fields.size
                )
                toolResult(gson.toJson(mapOf("symbol" to symbol, "results" to listOf(result), "count" to 1)))
            } else {
                toolResult(gson.toJson(mapOf("symbol" to symbol, "results" to emptyList<Any>(), "count" to 0)))
            }
        }

        server.addTool(
            name = "smali_search_symbols",
            description = "Search for classes, methods, or fields by name pattern. Returns up to 100 matching symbols.",
            inputSchema = toolSchema(
                properties = buildJsonObject {
                    putJsonObject("pattern") {
                        put("type", "string")
                        put("description", "Search pattern (case-insensitive substring match)")
                    }
                },
                required = listOf("pattern")
            )
        ) { request ->
            if (workspaceSymbolProvider == null) return@addTool toolError("No index loaded. Call smali_index first.")

            val pattern = request.arguments?.get("pattern")?.jsonPrimitive?.content
                ?: return@addTool toolError("Missing required argument: 'pattern'")

            val symbols = workspaceSymbolProvider!!.search(pattern)
            val results = symbols.map { symbol ->
                val simpleName = when (symbol.kind) {
                    org.eclipse.lsp4j.SymbolKind.Class ->
                        symbol.name.removePrefix("L").removeSuffix(";").substringAfterLast('/')
                    else ->
                        symbol.name.substringAfterLast('.')
                }
                mapOf(
                    "type" to when (symbol.kind) {
                        org.eclipse.lsp4j.SymbolKind.Class -> "class"
                        org.eclipse.lsp4j.SymbolKind.Method -> "method"
                        org.eclipse.lsp4j.SymbolKind.Field -> "field"
                        else -> "unknown"
                    },
                    "name" to simpleName,
                    "fullName" to symbol.name,
                    "containerName" to symbol.containerName,
                    "uri" to symbol.location.uri
                )
            }
            toolResult(gson.toJson(mapOf("pattern" to pattern, "results" to results, "count" to results.size)))
        }

        server.addTool(
            name = "smali_get_stats",
            description = "Get statistics about the indexed codebase: total classes, methods, fields.",
            inputSchema = toolSchema(properties = buildJsonObject {}, required = emptyList())
        ) { _ ->
            requireIndex() ?: return@addTool toolError("No index loaded. Call smali_index first.")

            val stats = index!!.getStats()
            toolResult(gson.toJson(mapOf(
                "indexedDirectory" to indexedDirectory,
                "classes" to stats.classes,
                "methods" to stats.methods,
                "fields" to stats.fields
            )))
        }

        server.addTool(
            name = "smali_find_references",
            description = "Find all references to a symbol at a specific position in a file.",
            inputSchema = positionToolSchema()
        ) { request ->
            if (referenceProvider == null) return@addTool toolError("No index loaded. Call smali_index first.")

            val (uri, line, character) = extractPosition(request.arguments)
                ?: return@addTool toolError("Missing required arguments: 'uri', 'line', 'character'")

            val references = referenceProvider!!.findReferences(uri, Position(line, character), true)
            val results = references.map { location ->
                mapOf(
                    "uri" to location.uri,
                    "range" to mapOf(
                        "start" to mapOf("line" to location.range.start.line, "character" to location.range.start.character),
                        "end" to mapOf("line" to location.range.end.line, "character" to location.range.end.character)
                    )
                )
            }
            toolResult(gson.toJson(mapOf("uri" to uri, "results" to results, "count" to results.size)))
        }

        server.addTool(
            name = "smali_hover",
            description = "Get hover information (type info, documentation) for a symbol at a specific position.",
            inputSchema = positionToolSchema()
        ) { request ->
            if (hoverProvider == null) return@addTool toolError("No index loaded. Call smali_index first.")

            val (uri, line, character) = extractPosition(request.arguments)
                ?: return@addTool toolError("Missing required arguments: 'uri', 'line', 'character'")

            val hover = hoverProvider!!.provideHover(uri, Position(line, character))
            if (hover == null) {
                toolResult(gson.toJson(mapOf("hover" to null)))
            } else {
                toolResult(gson.toJson(mapOf(
                    "contents" to hover.contents.right.value,
                    "range" to hover.range?.let { range ->
                        mapOf(
                            "start" to mapOf("line" to range.start.line, "character" to range.start.character),
                            "end" to mapOf("line" to range.end.line, "character" to range.end.character)
                        )
                    }
                )))
            }
        }

        server.addTool(
            name = "smali_diagnostics",
            description = "Get diagnostics (errors, warnings) for a smali file. Returns issues like undefined classes, invalid references.",
            inputSchema = toolSchema(
                properties = buildJsonObject {
                    putJsonObject("uri") {
                        put("type", "string")
                        put("description", "File URI (e.g., 'file:///path/to/file.smali')")
                    }
                    putJsonObject("content") {
                        put("type", "string")
                        put("description", "File content to analyze")
                    }
                },
                required = listOf("uri", "content")
            )
        ) { request ->
            if (diagnosticProvider == null) return@addTool toolError("No index loaded. Call smali_index first.")

            val uri = request.arguments?.get("uri")?.jsonPrimitive?.content
                ?: return@addTool toolError("Missing required argument: 'uri'")
            val content = request.arguments?.get("content")?.jsonPrimitive?.content
                ?: return@addTool toolError("Missing required argument: 'content'")

            val diagnostics = diagnosticProvider!!.computeDiagnostics(uri, content)
            val results = diagnostics.map { diagnostic ->
                mapOf(
                    "range" to mapOf(
                        "start" to mapOf("line" to diagnostic.range.start.line, "character" to diagnostic.range.start.character),
                        "end" to mapOf("line" to diagnostic.range.end.line, "character" to diagnostic.range.end.character)
                    ),
                    "severity" to diagnostic.severity.value,
                    "message" to diagnostic.message
                )
            }
            toolResult(gson.toJson(mapOf("diagnostics" to results, "count" to results.size)))
        }

        server.addTool(
            name = "smali_document_symbols",
            description = "Get document outline/symbols for a smali file. Returns class, methods, fields.",
            inputSchema = toolSchema(
                properties = buildJsonObject {
                    putJsonObject("uri") {
                        put("type", "string")
                        put("description", "File URI (e.g., 'file:///path/to/file.smali')")
                    }
                },
                required = listOf("uri")
            )
        ) { request ->
            requireIndex() ?: return@addTool toolError("No index loaded. Call smali_index first.")

            val uri = request.arguments?.get("uri")?.jsonPrimitive?.content
                ?: return@addTool toolError("Missing required argument: 'uri'")

            val file = index!!.findFileByUri(uri)
                ?: return@addTool toolError("File not found in index: $uri")

            val symbols = mutableListOf<Map<String, Any?>>()

            symbols.add(mapOf(
                "kind" to "class",
                "name" to file.classDefinition.name,
                "superClass" to file.classDefinition.superClass,
                "interfaces" to file.classDefinition.interfaces,
                "range" to mapOf(
                    "start" to mapOf("line" to file.classDefinition.range.start.line, "character" to file.classDefinition.range.start.character),
                    "end" to mapOf("line" to file.classDefinition.range.end.line, "character" to file.classDefinition.range.end.character)
                )
            ))

            file.methods.forEach { method ->
                symbols.add(mapOf(
                    "kind" to "method",
                    "name" to method.name,
                    "descriptor" to method.descriptor,
                    "className" to file.classDefinition.name,
                    "range" to mapOf(
                        "start" to mapOf("line" to method.range.start.line, "character" to method.range.start.character),
                        "end" to mapOf("line" to method.range.end.line, "character" to method.range.end.character)
                    )
                ))
            }

            file.fields.forEach { field ->
                symbols.add(mapOf(
                    "kind" to "field",
                    "name" to field.name,
                    "type" to field.type,
                    "className" to file.classDefinition.name,
                    "range" to mapOf(
                        "start" to mapOf("line" to field.range.start.line, "character" to field.range.start.character),
                        "end" to mapOf("line" to field.range.end.line, "character" to field.range.end.character)
                    )
                ))
            }

            toolResult(gson.toJson(mapOf("uri" to uri, "symbols" to symbols, "count" to symbols.size)))
        }
    }

    // --- Helpers ---

    private fun requireIndex(): WorkspaceIndex? = index

    private fun toolResult(text: String): CallToolResult {
        return CallToolResult(content = listOf(TextContent(text)))
    }

    private fun toolError(message: String): CallToolResult {
        return CallToolResult(content = listOf(TextContent(message)), isError = true)
    }

    private fun toolSchema(properties: JsonObject, required: List<String>): ToolSchema {
        return ToolSchema(properties = properties, required = required)
    }

    private fun positionToolSchema(): ToolSchema {
        return toolSchema(
            properties = buildJsonObject {
                putJsonObject("uri") {
                    put("type", "string")
                    put("description", "File URI (e.g., 'file:///path/to/file.smali')")
                }
                putJsonObject("line") {
                    put("type", "number")
                    put("description", "Line number (0-indexed)")
                }
                putJsonObject("character") {
                    put("type", "number")
                    put("description", "Character position (0-indexed)")
                }
            },
            required = listOf("uri", "line", "character")
        )
    }

    private fun extractPosition(arguments: JsonObject?): Triple<String, Int, Int>? {
        val uri = arguments?.get("uri")?.jsonPrimitive?.content ?: return null
        val line = arguments.get("line")?.jsonPrimitive?.int ?: return null
        val character = arguments.get("character")?.jsonPrimitive?.int ?: return null
        return Triple(uri, line, character)
    }
}
