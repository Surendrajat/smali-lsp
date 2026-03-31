package xyz.surendrajat.smalilsp.integration.lsp

import xyz.surendrajat.smalilsp.SmaliLanguageServer
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.TimeUnit

import xyz.surendrajat.smalilsp.integration.lsp.E2ETestHarness
import xyz.surendrajat.smalilsp.integration.lsp.TestLSPClient
import xyz.surendrajat.smalilsp.integration.lsp.TestWorkspace
/**
 * Test harness for E2E testing of the Smali LSP server.
 * Manages server lifecycle, client connection, and provides convenient methods for common operations.
 * Uses direct method calls (not JSON-RPC) for simplicity and better error messages.
 */
class E2ETestHarness(val workspace: TestWorkspace) {
    
    lateinit var server: SmaliLanguageServer
    lateinit var client: TestLSPClient
    
    private var isInitialized = false
    private var isShutdown = false
    
    /**
     * Initialize the server and connect the client
     */
    fun initialize(
        clientCapabilities: ClientCapabilities = createDefaultClientCapabilities(),
        workspaceFolders: List<WorkspaceFolder>? = null
    ): E2ETestHarness {
        
        if (isInitialized) {
            throw IllegalStateException("Server already initialized")
        }
        
        // Create server and client
        server = SmaliLanguageServer()
        client = TestLSPClient()
        
        // Connect client to server
        server.connect(client)
        
        // Send initialize request
        val initParams = InitializeParams().apply {
            rootUri = workspace.rootUri
            capabilities = clientCapabilities
            if (workspaceFolders != null) {
                setWorkspaceFolders(workspaceFolders)
            }
        }
        
        server.initialize(initParams).get(30, TimeUnit.SECONDS)
        
        // Send initialized notification
        server.initialized(InitializedParams())
        
        isInitialized = true
        
        // Give server time to start indexing
        Thread.sleep(500)
        
        return this
    }
    
    /**
     * Create default client capabilities (support all features)
     */
    private fun createDefaultClientCapabilities(): ClientCapabilities {
        return ClientCapabilities().apply {
            workspace = WorkspaceClientCapabilities().apply {
                workspaceEdit = WorkspaceEditCapabilities(true)
                didChangeConfiguration = DidChangeConfigurationCapabilities(true)
                didChangeWatchedFiles = DidChangeWatchedFilesCapabilities(true)
            }
            
            textDocument = TextDocumentClientCapabilities()
        }
    }
    
    // ===== File Operations =====
    
    /**
     * Open a file in the server
     */
    fun openFile(relativePath: String, languageId: String = "smali", version: Int = 1): String {
        val uri = workspace.getFileUri(relativePath)
        val content = workspace.readFile(relativePath)
        
        server.textDocumentService.didOpen(DidOpenTextDocumentParams(
            TextDocumentItem(uri, languageId, version, content)
        ))
        
        // Give server time to process
        Thread.sleep(100)
        
        return uri
    }
    
    /**
     * Change file content
     */
    fun changeFile(uri: String, newContent: String, version: Int) {
        server.textDocumentService.didChange(DidChangeTextDocumentParams(
            VersionedTextDocumentIdentifier(uri, version),
            listOf(TextDocumentContentChangeEvent(newContent))
        ))
        
        // Give server time to process
        Thread.sleep(100)
    }
    
    /**
     * Close a file
     */
    fun closeFile(uri: String) {
        server.textDocumentService.didClose(DidCloseTextDocumentParams(
            TextDocumentIdentifier(uri)
        ))
    }
    
    // ===== Language Features =====
    
    /**
     * Go to definition
     */
    fun gotoDefinition(uri: String, line: Int, character: Int, timeoutSeconds: Long = 5): List<Location> {
        val result = server.textDocumentService.definition(DefinitionParams(
            TextDocumentIdentifier(uri),
            Position(line, character)
        )).get(timeoutSeconds, TimeUnit.SECONDS)
        
        return when {
            result == null -> emptyList()
            result.isLeft -> result.left.toList()
            result.isRight -> emptyList()  // We don't use LocationLink
            else -> emptyList()
        }
    }
    
    /**
     * Go to definition with position
     */
    fun gotoDefinition(uri: String, position: Position, timeoutSeconds: Long = 5): List<Location> {
        return gotoDefinition(uri, position.line, position.character, timeoutSeconds)
    }
    
    /**
     * Find references
     */
    fun findReferences(
        uri: String, 
        line: Int, 
        character: Int, 
        includeDeclaration: Boolean = true,
        timeoutSeconds: Long = 10
    ): List<Location> {
        val result = server.textDocumentService.references(ReferenceParams(
            TextDocumentIdentifier(uri),
            Position(line, character),
            ReferenceContext(includeDeclaration)
        )).get(timeoutSeconds, TimeUnit.SECONDS)
        
        return result?.toList() ?: emptyList()
    }
    
    /**
     * Find references with position
     */
    fun findReferences(
        uri: String, 
        position: Position, 
        includeDeclaration: Boolean = true,
        timeoutSeconds: Long = 10
    ): List<Location> {
        return findReferences(uri, position.line, position.character, includeDeclaration, timeoutSeconds)
    }
    
    /**
     * Hover
     */
    fun hover(uri: String, line: Int, character: Int, timeoutSeconds: Long = 2): Hover? {
        return server.textDocumentService.hover(HoverParams(
            TextDocumentIdentifier(uri),
            Position(line, character)
        )).get(timeoutSeconds, TimeUnit.SECONDS)
    }
    
    /**
     * Hover with position
     */
    fun hover(uri: String, position: Position, timeoutSeconds: Long = 2): Hover? {
        return hover(uri, position.line, position.character, timeoutSeconds)
    }
    
    /**
     * Get document symbols
     */
    fun documentSymbols(uri: String, timeoutSeconds: Long = 5): List<DocumentSymbol> {
        val result = server.textDocumentService.documentSymbol(
            DocumentSymbolParams(TextDocumentIdentifier(uri))
        ).get(timeoutSeconds, TimeUnit.SECONDS) ?: return emptyList()
        
        return result.mapNotNull { 
            if (it.isRight) it.right else null 
        }
    }
    
    /**
     * Get workspace symbols
     */
    fun workspaceSymbols(query: String, timeoutSeconds: Long = 10): List<SymbolInformation> {
        val result = server.workspaceService.symbol(
            WorkspaceSymbolParams(query)
        ).get(timeoutSeconds, TimeUnit.SECONDS)
        
        return when {
            result == null -> emptyList()
            result.isLeft -> result.left.toList()
            result.isRight -> emptyList()  // WorkspaceSymbol not currently used
            else -> emptyList()
        }
    }
    
    // ===== Diagnostics =====
    
    /**
     * Get diagnostics for URI
     */
    fun getDiagnostics(uri: String): List<Diagnostic> {
        return client.getDiagnostics(uri)
    }
    
    /**
     * Wait for diagnostics to be published
     */
    fun waitForDiagnostics(uri: String, timeoutMs: Long = 5000): List<Diagnostic> {
        return client.waitForDiagnostics(uri, timeoutMs)
    }
    
    // ===== Helpers =====
    
    /**
     * Wait for indexing to complete (simple sleep-based)
     */
    fun waitForIndexing(seconds: Int = 5) {
        Thread.sleep(seconds * 1000L)
    }
    
    /**
     * Get workspace statistics
     */
    fun getWorkspaceStats(): TestWorkspace.WorkspaceStats {
        return workspace.getStats()
    }
    
    /**
     * Measure operation time
     */
    fun <T> measureTime(operation: () -> T): Pair<T, Long> {
        val start = System.currentTimeMillis()
        val result = operation()
        val elapsed = System.currentTimeMillis() - start
        return result to elapsed
    }
    
    // ===== Shutdown =====
    
    /**
     * Shutdown the server
     * 
     * NOTE: We intentionally DO NOT call server.exit() because that calls System.exit(0)
     * which would terminate the entire JVM and kill the test runner!
     */
    fun shutdown() {
        if (!isInitialized) {
            return
        }
        
        if (isShutdown) {
            return
        }
        
        try {
            server.shutdown().get(5, TimeUnit.SECONDS)
            // DO NOT call server.exit() - it calls System.exit(0) which kills the JVM!
            isShutdown = true
        } catch (e: Exception) {
            println("Error during shutdown: ${e.message}")
        }
    }
    
    /**
     * Check if server is initialized
     */
    fun checkInitialized(): Boolean = isInitialized
    
    /**
     * Cleanup (shutdown + cleanup workspace if temp)
     */
    fun cleanup() {
        shutdown()
        if (workspace.baseDir.name.startsWith("smali-lsp-test")) {
            workspace.cleanup()
        }
    }
}

/**
 * Convenience extension for running E2E tests with automatic cleanup
 */
inline fun <T> withE2ETest(
    workspace: TestWorkspace,
    block: E2ETestHarness.() -> T
): T {
    val harness = E2ETestHarness(workspace).initialize()
    return try {
        harness.block()
    } finally {
        harness.cleanup()
    }
}
