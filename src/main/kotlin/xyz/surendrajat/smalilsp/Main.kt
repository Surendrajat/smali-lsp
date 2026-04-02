package xyz.surendrajat.smalilsp

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.*
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.indexer.WorkspaceScanner
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.providers.HoverProvider
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import xyz.surendrajat.smalilsp.cli.McpMode
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.runBlocking

/**
 * Main entry point for Smali Language Server.
 * 
 * Architecture:
 * - Communicates via LSP protocol over stdio (default mode)
 * - OR runs as MCP server for AI agent integration (keeps index in memory, <10ms queries)
 * - Indexes workspace on initialization
 * - Provides goto definition, hover, find references, diagnostics
 *
 * Usage:
 *   java -jar smali-lsp-all.jar       (LSP mode - VS Code extension)
 *   java -jar smali-lsp-all.jar --mcp (MCP server - AI agent integration)
 */
fun main(args: Array<String>) {
    // Check for MCP server mode
    if (args.isNotEmpty() && args[0] == "--mcp") {
        McpMode().run()
        return
    }
    
    // Configure Java Util Logging to use our properties file
    // This prevents LSP4J from logging to stdout which corrupts LSP protocol
    System.setProperty("java.util.logging.config.file", 
        SmaliLanguageServer::class.java.getResource("/logging.properties")?.path ?: "")
    
    val server = SmaliLanguageServer()
    val launcher = LSPLauncher.createServerLauncher(server, System.`in`, System.out)
    
    val client = launcher.remoteProxy
    server.connect(client)
    
    // Start listening - blocks until connection closes
    launcher.startListening()
}

/**
 * Smali Language Server implementation.
 */
class SmaliLanguageServer : LanguageServer {
    
    private val logger = LoggerFactory.getLogger(SmaliLanguageServer::class.java)
    private lateinit var client: LanguageClient
    
    // Core components
    private val index = WorkspaceIndex()
    private val scanner = WorkspaceScanner(index)
    private val definitionProvider = DefinitionProvider(index)
    private val hoverProvider = HoverProvider(index)
    private val referenceProvider = ReferenceProvider(index)
    
    // Text document service
    private val textDocumentService = SmaliTextDocumentService(
        index,
        definitionProvider,
        hoverProvider,
        referenceProvider
    )
    
    // Workspace service
    private val workspaceService = SmaliWorkspaceService(index)
    
    /**
     * Connect to client.
     */
    fun connect(client: LanguageClient) {
        this.client = client
        textDocumentService.connect(client)
    }
    
    /**
     * Initialize server.
     * Client sends workspace folders and capabilities.
     */
    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        logger.info("Initializing Smali Language Server")
        logger.info("Client: ${params.clientInfo?.name} ${params.clientInfo?.version}")
        logger.info("Workspace folders: ${params.workspaceFolders?.size ?: 0}")
        
        // Index workspace folders
        val workspaceFolders = params.workspaceFolders ?: emptyList()
        if (workspaceFolders.isNotEmpty()) {
            return CompletableFuture.supplyAsync {
                try {
                    indexWorkspace(workspaceFolders)
                    createInitializeResult()
                } catch (e: Exception) {
                    logger.error("Failed to initialize workspace", e)
                    createInitializeResult()
                }
            }
        }
        
        return CompletableFuture.completedFuture(createInitializeResult())
    }
    
    /**
     * Index all workspace folders.
     */
    private fun indexWorkspace(folders: List<WorkspaceFolder>) = runBlocking {
        logger.info("Indexing ${folders.size} workspace folder(s)...")
        
        for (folder in folders) {
            try {
                val uri = folder.uri
                val path = uri.replace("file://", "").replace("file:", "")
                val dir = File(path)
                
                if (dir.exists() && dir.isDirectory) {
                    logger.info("Scanning: ${dir.absolutePath}")
                    
                    val startTime = System.currentTimeMillis()
                    val result = scanner.scanDirectory(dir) { processed, total ->
                        if (processed % 1000 == 0 || processed == total) {
                            val percent = processed * 100 / total
                            logger.info("Indexing: $processed/$total ($percent%)")
                        }
                    }
                    
                    val duration = System.currentTimeMillis() - startTime
                    val stats = index.getStats()
                    
                    logger.info("Indexed ${result.filesSucceeded} files in ${duration}ms")
                    logger.info("  Rate: ${result.filesPerSecond} files/sec")
                    logger.info("  Classes: ${stats.classes}")
                    logger.info("  Methods: ${stats.methods}")
                    logger.info("  Fields: ${stats.fields}")
                    
                    if (result.filesFailed > 0) {
                        logger.warn("  Failed: ${result.filesFailed} files")
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to index folder: ${folder.uri}", e)
            }
        }
    }
    
    /**
     * Create initialize result with server capabilities.
     */
    private fun createInitializeResult(): InitializeResult {
        val capabilities = ServerCapabilities()
        
        // Text document sync
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Full)
        
        // Goto definition
        capabilities.setDefinitionProvider(true)
        
        // Hover
        capabilities.setHoverProvider(true)
        
        // Find references
        capabilities.setReferencesProvider(true)
        
        // Document symbols
        capabilities.setDocumentSymbolProvider(true)
        
        // Workspace symbols
        capabilities.setWorkspaceSymbolProvider(true)
        
        val serverInfo = ServerInfo("Smali Language Server", "1.0.0")
        return InitializeResult(capabilities, serverInfo)
    }
    
    /**
     * Called after initialize response.
     */
    override fun initialized(params: InitializedParams) {
        logger.info("Server initialized and ready")
    }
    
    /**
     * Shutdown server.
     */
    override fun shutdown(): CompletableFuture<Any> {
        logger.info("Shutting down server")
        return CompletableFuture.completedFuture(null)
    }
    
    /**
     * Exit server.
     */
    override fun exit() {
        logger.info("Server exiting")
        System.exit(0)
    }
    
    override fun getTextDocumentService(): TextDocumentService {
        return textDocumentService
    }
    
    override fun getWorkspaceService(): WorkspaceService {
        return workspaceService
    }
}
