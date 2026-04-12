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
import xyz.surendrajat.smalilsp.providers.CallHierarchyProvider
import xyz.surendrajat.smalilsp.providers.TypeHierarchyProvider
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
 *   java -jar smali-lsp.jar mcp
 *
 * MCP config (claude_desktop_config.json / cursor settings):
 *   {
 *     "mcpServers": {
 *       "smali-lsp": {
 *         "command": "java",
 *         "args": ["-jar", "/path/to/smali-lsp.jar", "mcp"]
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
    private var callHierarchyProvider: CallHierarchyProvider? = null
    private var typeHierarchyProvider: TypeHierarchyProvider? = null

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
            callHierarchyProvider = null
            typeHierarchyProvider = null
            xyz.surendrajat.smalilsp.util.StringPool.clear()

            val newIndex = WorkspaceIndex()
            val scanner = WorkspaceScanner(newIndex)
            val result = scanner.scanDirectory(dir) { _, _ -> }

            index = newIndex
            definitionProvider = DefinitionProvider(newIndex)
            hoverProvider = HoverProvider(newIndex)
            referenceProvider = ReferenceProvider(newIndex)
            diagnosticProvider = DiagnosticProvider(parser, newIndex)
            workspaceSymbolProvider = WorkspaceSymbolProvider(newIndex)
            callHierarchyProvider = CallHierarchyProvider(newIndex)
            typeHierarchyProvider = TypeHierarchyProvider(newIndex)

            indexedDirectory = dir.absolutePath
            val duration = System.currentTimeMillis() - startTime
            val stats = newIndex.getStats()

            val failedMsg = if (result.filesFailed > 0) "Failed: ${result.filesFailed}\n" else ""
            toolResult(
                "Successfully indexed $directory\n" +
                        "Files: ${result.filesSucceeded}/${result.filesProcessed}\n" +
                        failedMsg +
                        "Time: ${duration}ms\n" +
                        "Classes: ${stats.classes}, Methods: ${stats.methods}, Fields: ${stats.fields}, Strings: ${stats.strings}\n\n" +
                        "Index is now in memory. Subsequent queries will be fast (<1s)."
            )
        }

        server.addTool(
            name = "smali_find_definition",
            description = "Find the definition of a class. Accepts fully qualified name (e.g., 'Lcom/example/MainActivity;') or simple name (e.g., 'MainActivity'). Returns class info including superclass, interfaces, methods, and fields.",
            inputSchema = toolSchema(
                properties = buildJsonObject {
                    putJsonObject("symbol") {
                        put("type", "string")
                        put("description", "Class name — fully qualified (e.g., 'Lcom/example/MainActivity;') or simple (e.g., 'MainActivity')")
                    }
                },
                required = listOf("symbol")
            )
        ) { request ->
            requireIndex() ?: return@addTool toolError("No index loaded. Call smali_index first.")

            val symbol = request.arguments?.get("symbol")?.jsonPrimitive?.content
                ?: return@addTool toolError("Missing required argument: 'symbol'")

            // Try exact match first (fully qualified), then fall back to simple name search
            val classFile = index!!.findClass(symbol)
            val matchedFiles = if (classFile != null) {
                listOf(classFile)
            } else {
                index!!.findClassesBySimpleName(symbol)
            }

            val results = matchedFiles.map { file ->
                mapOf(
                    "type" to "class",
                    "name" to file.classDefinition.name,
                    "uri" to file.uri,
                    "superClass" to file.classDefinition.superClass,
                    "interfaces" to file.classDefinition.interfaces,
                    "methods" to file.methods.size,
                    "fields" to file.fields.size
                )
            }
            toolResult(gson.toJson(mapOf("symbol" to symbol, "results" to results, "count" to results.size)))
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
                "fields" to stats.fields,
                "strings" to stats.strings
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

        // --- String Search ---

        server.addTool(
            name = "smali_search_strings",
            description = "Search for string literals (const-string instructions) across all indexed smali files. " +
                    "Useful for finding URLs, API keys, error messages, hardcoded paths, etc.",
            inputSchema = toolSchema(
                properties = buildJsonObject {
                    putJsonObject("query") {
                        put("type", "string")
                        put("description", "Substring to search for in string literals (case-insensitive)")
                    }
                    putJsonObject("max_results") {
                        put("type", "number")
                        put("description", "Maximum results to return (default: 100)")
                    }
                },
                required = listOf("query")
            )
        ) { request ->
            requireIndex() ?: return@addTool toolError("No index loaded. Call smali_index first.")

            val query = request.arguments?.get("query")?.jsonPrimitive?.content
                ?: return@addTool toolError("Missing required argument: 'query'")
            val maxResults = request.arguments?.get("max_results")?.jsonPrimitive?.int ?: 100

            val results = index!!.searchStrings(query, maxResults).map { result ->
                mapOf(
                    "value" to result.value,
                    "uri" to result.location.uri,
                    "file" to result.location.uri.substringAfterLast('/'),
                    "line" to (result.location.range.start.line + 1)
                )
            }
            toolResult(gson.toJson(mapOf("query" to query, "results" to results, "count" to results.size)))
        }

        // --- Call Hierarchy ---

        server.addTool(
            name = "smali_call_graph",
            description = "Get call hierarchy (callers and callees) for a method. " +
                    "Specify a class and method to find who calls it (incoming) and what it calls (outgoing).",
            inputSchema = toolSchema(
                properties = buildJsonObject {
                    putJsonObject("class_name") {
                        put("type", "string")
                        put("description", "Fully qualified class name (e.g., 'Lcom/example/MyClass;')")
                    }
                    putJsonObject("method_name") {
                        put("type", "string")
                        put("description", "Method name (e.g., 'onCreate')")
                    }
                    putJsonObject("descriptor") {
                        put("type", "string")
                        put("description", "Method descriptor (e.g., '(Landroid/os/Bundle;)V'). If omitted, matches first method with given name.")
                    }
                    putJsonObject("direction") {
                        put("type", "string")
                        put("description", "Direction: 'incoming' (callers), 'outgoing' (callees), or 'both' (default: 'both')")
                    }
                },
                required = listOf("class_name", "method_name")
            )
        ) { request ->
            if (callHierarchyProvider == null) return@addTool toolError("No index loaded. Call smali_index first.")

            val className = request.arguments?.get("class_name")?.jsonPrimitive?.content
                ?: return@addTool toolError("Missing required argument: 'class_name'")
            val methodName = request.arguments?.get("method_name")?.jsonPrimitive?.content
                ?: return@addTool toolError("Missing required argument: 'method_name'")
            val descriptor = request.arguments?.get("descriptor")?.jsonPrimitive?.content
            val direction = request.arguments?.get("direction")?.jsonPrimitive?.content ?: "both"
            if (direction !in listOf("incoming", "outgoing", "both")) {
                return@addTool toolError("Invalid direction: '$direction'. Must be 'incoming', 'outgoing', or 'both'.")
            }

            val classFile = index!!.findClass(className)
                ?: return@addTool toolError("Class not found: $className")

            val method = if (descriptor != null) {
                classFile.methods.find { it.name == methodName && it.descriptor == descriptor }
            } else {
                classFile.methods.find { it.name == methodName }
            } ?: return@addTool toolError("Method not found: $className->$methodName${descriptor ?: ""}")

            val item = org.eclipse.lsp4j.CallHierarchyItem().apply {
                name = "${method.name}${method.descriptor}"
                kind = org.eclipse.lsp4j.SymbolKind.Method
                uri = classFile.uri
                range = method.range
                selectionRange = method.range
                detail = className
            }

            val result = mutableMapOf<String, Any>(
                "class" to className,
                "method" to "${method.name}${method.descriptor}"
            )

            if (direction == "incoming" || direction == "both") {
                val incoming = callHierarchyProvider!!.incomingCalls(item)
                result["incoming_calls"] = incoming.map { call ->
                    mapOf(
                        "from_class" to (call.from.detail ?: "unknown"),
                        "from_method" to call.from.name,
                        "uri" to call.from.uri,
                        "call_sites" to call.fromRanges.size
                    )
                }
                result["incoming_count"] = incoming.size
                result["_note_incoming"] = "incoming_count = unique calling methods"
            }

            if (direction == "outgoing" || direction == "both") {
                val outgoing = callHierarchyProvider!!.outgoingCalls(item)
                result["outgoing_calls"] = outgoing.map { call ->
                    mapOf(
                        "to_class" to (call.to.detail ?: "unknown"),
                        "to_method" to call.to.name,
                        "uri" to call.to.uri,
                        "call_sites" to call.fromRanges.size
                    )
                }
                result["outgoing_count"] = outgoing.size
                result["_note_outgoing"] = "outgoing_count = unique called methods"
            }

            toolResult(gson.toJson(result))
        }

        // --- Xref Summary ---

        server.addTool(
            name = "smali_xref_summary",
            description = "Get a cross-reference summary for a class: who extends it, who implements it, " +
                    "who calls its methods, who accesses its fields. Provides a high-level usage overview.",
            inputSchema = toolSchema(
                properties = buildJsonObject {
                    putJsonObject("class_name") {
                        put("type", "string")
                        put("description", "Fully qualified class name (e.g., 'Lcom/example/MyClass;')")
                    }
                },
                required = listOf("class_name")
            )
        ) { request ->
            requireIndex() ?: return@addTool toolError("No index loaded. Call smali_index first.")

            val className = request.arguments?.get("class_name")?.jsonPrimitive?.content
                ?: return@addTool toolError("Missing required argument: 'class_name'")

            val classFile = index!!.findClass(className)

            // Find subclasses and implementors (O(1) index lookups)
            val subclasses = index!!.getDirectSubclasses(className).toList()
            val implementors = index!!.getDirectImplementors(className).toList()

            // Find all method callers using reverse usage indexes
            val methodCallers = mutableMapOf<String, MutableSet<String>>()
            val classFileMethods = classFile?.methods ?: emptyList()
            for (method in classFileMethods) {
                val key = "${method.name}${method.descriptor}"
                val usages = index!!.findMethodUsages(className, method.name, method.descriptor)
                for (loc in usages) {
                    val callerClass = index!!.findClassNameByUri(loc.uri) ?: continue
                    methodCallers.computeIfAbsent(key) { mutableSetOf() }.add(callerClass)
                }
            }

            // Find all field accessors using reverse usage indexes
            val fieldAccessors = mutableMapOf<String, MutableSet<String>>()
            val classFileFields = classFile?.fields ?: emptyList()
            for (field in classFileFields) {
                val usages = index!!.findFieldUsages(className, field.name)
                for (loc in usages) {
                    val accessorClass = index!!.findClassNameByUri(loc.uri) ?: continue
                    fieldAccessors.computeIfAbsent(field.name) { mutableSetOf() }.add(accessorClass)
                }
            }

            // Find class usages (type references: new-instance, check-cast, etc.)
            val typeUsages = index!!.findClassUsages(className)

            val result = mapOf(
                "class" to className,
                "defined" to (classFile != null),
                "subclasses" to subclasses,
                "implementors" to implementors,
                "method_callers" to methodCallers.map { (method, callers) ->
                    mapOf("method" to method, "callers" to callers.toList(), "caller_class_count" to callers.size)
                },
                "field_accessors" to fieldAccessors.map { (field, accessors) ->
                    mapOf("field" to field, "accessors" to accessors.toList(), "accessor_class_count" to accessors.size)
                },
                "type_references" to typeUsages.toList(),
                "summary" to mapOf(
                    "subclass_count" to subclasses.size,
                    "implementor_count" to implementors.size,
                    "method_caller_count" to methodCallers.values.sumOf { it.size },
                    "field_accessor_count" to fieldAccessors.values.sumOf { it.size },
                    "type_reference_count" to typeUsages.size
                )
            )

            toolResult(gson.toJson(result))
        }

        // --- Type Hierarchy ---

        server.addTool(
            name = "smali_type_hierarchy",
            description = "Get the type hierarchy for a class: supertypes (parent class + interfaces) and subtypes " +
                    "(direct subclasses + implementors). Useful for understanding inheritance chains.",
            inputSchema = toolSchema(
                properties = buildJsonObject {
                    putJsonObject("class_name") {
                        put("type", "string")
                        put("description", "Fully qualified class name (e.g., 'Lcom/example/MyClass;')")
                    }
                    putJsonObject("direction") {
                        put("type", "string")
                        put("description", "Direction: 'supertypes', 'subtypes', or 'both' (default: 'both')")
                    }
                },
                required = listOf("class_name")
            )
        ) { request ->
            requireIndex() ?: return@addTool toolError("No index loaded. Call smali_index first.")

            val className = request.arguments?.get("class_name")?.jsonPrimitive?.content
                ?: return@addTool toolError("Missing required argument: 'class_name'")
            val direction = request.arguments?.get("direction")?.jsonPrimitive?.content ?: "both"
            if (direction !in listOf("supertypes", "subtypes", "both")) {
                return@addTool toolError("Invalid direction: '$direction'. Must be 'supertypes', 'subtypes', or 'both'.")
            }

            val provider = typeHierarchyProvider
                ?: return@addTool toolError("No index loaded. Call smali_index first.")

            val classFile = index!!.findClass(className)
            if (classFile == null) {
                return@addTool toolResult(gson.toJson(mapOf(
                    "class" to className,
                    "error" to "Class not found in index"
                )))
            }

            val classDef = classFile.classDefinition
            val item = org.eclipse.lsp4j.TypeHierarchyItem(
                className.removePrefix("L").removeSuffix(";").substringAfterLast('/'),
                org.eclipse.lsp4j.SymbolKind.Class,
                classFile.uri,
                classDef.range,
                classDef.range,
                className
            )

            val result = mutableMapOf<String, Any>(
                "class" to className
            )

            if (direction == "supertypes" || direction == "both") {
                val supertypes = provider.supertypes(item).map { st ->
                    mapOf(
                        "name" to st.name,
                        "class" to (st.detail ?: ""),
                        "kind" to st.kind.toString()
                    )
                }
                result["supertypes"] = supertypes
            }

            if (direction == "subtypes" || direction == "both") {
                val subtypes = provider.subtypes(item).map { st ->
                    mapOf(
                        "name" to st.name,
                        "class" to (st.detail ?: ""),
                        "kind" to st.kind.toString()
                    )
                }
                result["subtypes"] = subtypes
            }

            toolResult(gson.toJson(result))
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
