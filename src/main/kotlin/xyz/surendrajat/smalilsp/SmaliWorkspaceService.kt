package xyz.surendrajat.smalilsp

import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.WorkspaceService
import org.slf4j.LoggerFactory
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.WorkspaceSymbolProvider
import java.io.File
import java.net.URI
import java.util.concurrent.CompletableFuture

/**
 * Workspace Service for Smali Language Server.
 * 
 * Handles workspace-level events:
 * - Configuration changes
 * - File system watchers
 * - Workspace symbol search
 * 
 * Provides:
 * - workspace/symbol: Search for symbols across the entire workspace
 */
class SmaliWorkspaceService(
    private val index: WorkspaceIndex,
    private val parser: SmaliParser = SmaliParser()
) : WorkspaceService {

    private val logger = LoggerFactory.getLogger(SmaliWorkspaceService::class.java)
    private val symbolProvider = WorkspaceSymbolProvider(index)
    
    /**
     * Configuration changed.
     */
    override fun didChangeConfiguration(params: DidChangeConfigurationParams) {
        logger.debug("didChangeConfiguration: ${params.settings}")
        // No configuration options yet
    }
    
    /**
     * Watched files changed on disk.
     *
     * Open files are kept in sync by didChange, but a file that was deleted,
     * created, or edited externally (e.g. by apktool regenerating a project)
     * will not trigger didChange — without handling this, go-to-definition
     * navigates to ghost URIs that no longer exist, and references to new
     * classes fail to resolve until the user opens them manually.
     */
    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
        if (params.changes.isEmpty()) return
        logger.debug("didChangeWatchedFiles: ${params.changes.size} change(s)")

        for (change in params.changes) {
            val uri = change.uri
            try {
                when (change.type) {
                    FileChangeType.Deleted -> {
                        if (index.removeFile(uri)) {
                            logger.debug("Removed from index (deleted): $uri")
                        }
                    }
                    FileChangeType.Created, FileChangeType.Changed -> {
                        val file = File(URI(uri))
                        if (!file.exists() || !file.isFile) continue
                        val smaliFile = parser.parse(uri, file.readText(Charsets.UTF_8))
                        if (smaliFile != null) {
                            index.indexFile(smaliFile)
                            logger.debug("Re-indexed from disk (${change.type}): $uri")
                        } else {
                            logger.warn("Failed to parse watched file: $uri")
                        }
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                logger.warn("Error handling watched file change for $uri: ${e.message}")
            }
        }
    }
    
    /**
     * Search workspace for symbols.
     * 
     * Supports:
     * - Fuzzy matching
     * - Case-insensitive search
     * - Classes, methods, and fields
     * - Fast performance (< 500ms for 4000+ files)
     * 
     * @param params Search query
     * @return List of matching symbols (up to 100 results)
     */
    override fun symbol(params: WorkspaceSymbolParams): CompletableFuture<Either<List<SymbolInformation>, List<WorkspaceSymbol>>> {
        return CompletableFuture.supplyAsync {
            logger.debug("workspace/symbol: query='${params.query}'")
            val symbols = symbolProvider.search(params.query)
            logger.debug("workspace/symbol: found ${symbols.size} matches")
            Either.forLeft<List<SymbolInformation>, List<WorkspaceSymbol>>(symbols)
        }
    }
}
