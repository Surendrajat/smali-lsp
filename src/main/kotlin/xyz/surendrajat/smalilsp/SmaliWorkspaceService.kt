package xyz.surendrajat.smalilsp

import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.WorkspaceService
import org.slf4j.LoggerFactory
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.providers.WorkspaceSymbolProvider
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
    private val index: WorkspaceIndex
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
     * Watched files changed.
     */
    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {
        logger.debug("didChangeWatchedFiles: ${params.changes.size} changes")
        // Files are re-indexed on didChange, no action needed
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
