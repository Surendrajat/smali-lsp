package xyz.surendrajat.smalilsp

import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.Either3
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.TextDocumentService
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.CallHierarchyProvider
import xyz.surendrajat.smalilsp.providers.CodeLensProvider
import xyz.surendrajat.smalilsp.providers.CompletionProvider
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.providers.DiagnosticProvider
import xyz.surendrajat.smalilsp.providers.HoverProvider
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import xyz.surendrajat.smalilsp.providers.RenameProvider
import xyz.surendrajat.smalilsp.providers.TypeHierarchyProvider
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

/**
 * Text Document Service for Smali Language Server.
 * 
 * Handles document lifecycle:
 * - didOpen: Parse and index file
 * - didChange: Re-parse and re-index
 * - didClose: Keep indexed (no removal, learned from V1)
 * 
 * Provides LSP features:
 * - textDocument/definition (goto definition)
 * - textDocument/hover (hover information)
 * - textDocument/references (find all references)
 * - textDocument/documentSymbol (document outline)
 */
class SmaliTextDocumentService(
    private val index: WorkspaceIndex,
    private val definitionProvider: DefinitionProvider,
    private val hoverProvider: HoverProvider,
    private val referenceProvider: ReferenceProvider,
    private val callHierarchyProvider: CallHierarchyProvider,
    private val typeHierarchyProvider: TypeHierarchyProvider,
    private val codeLensProvider: CodeLensProvider,
    private val completionProvider: CompletionProvider,
    private val renameProvider: RenameProvider
) : TextDocumentService {
    
    private val logger = LoggerFactory.getLogger(SmaliTextDocumentService::class.java)
    private val parser = SmaliParser()
    private val diagnosticProvider = DiagnosticProvider(parser, index)
    private var client: LanguageClient? = null

    /** Track open file URIs so we can refresh diagnostics after indexing completes. */
    private val openFiles = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Whether workspace indexing has completed. Semantic diagnostics are suppressed until true. */
    @Volatile
    var indexingComplete: Boolean = false

    /**
     * Connect to client for sending notifications.
     */
    fun connect(client: LanguageClient) {
        this.client = client
    }

    /**
     * Re-publish diagnostics for all currently open files.
     * Called after workspace indexing completes to clear stale false-positive warnings.
     */
    fun refreshDiagnosticsForOpenFiles() {
        logger.info("Refreshing diagnostics for ${openFiles.size} open file(s)")
        for (uri in openFiles) {
            try {
                val content = index.getDocumentContent(uri) ?: continue
                val diagnostics = diagnosticProvider.computeDiagnostics(uri, content)
                publishDiagnostics(uri, diagnostics)
            } catch (e: Exception) {
                logger.error("Error refreshing diagnostics for $uri", e)
            }
        }
    }
    
    /**
     * Publish diagnostics to client if connected.
     */
    private fun publishDiagnostics(uri: String, diagnostics: List<Diagnostic>) {
        client?.publishDiagnostics(PublishDiagnosticsParams(uri, diagnostics))
    }
    
    /**
     * Document opened - parse and index.
     */
    override fun didOpen(params: DidOpenTextDocumentParams) {
        val uri = params.textDocument.uri
        val content = params.textDocument.text

        logger.debug("didOpen: $uri")
        openFiles.add(uri)
        index.setDocumentContent(uri, content)

        try {
            // Parse with error collection for diagnostics
            val parseResult = parser.parseWithErrors(uri, content)
            
            // Index if not already indexed from workspace scan
            val existing = index.findFileByUri(uri)
            if (existing == null) {
                parseResult.smaliFile?.let { smaliFile ->
                    index.indexFile(smaliFile)
                    logger.debug("Indexed: $uri (${smaliFile.classDefinition.name})")
                } ?: logger.warn("Failed to parse: $uri")
            } else {
                logger.debug("Already indexed: $uri")
            }

            // Publish diagnostics - semantic checks only when indexing is complete
            val diagnostics = if (indexingComplete) {
                diagnosticProvider.computeDiagnosticsFromParseResult(uri, parseResult)
            } else {
                diagnosticProvider.computeSyntaxDiagnosticsFromParseResult(parseResult)
            }
            publishDiagnostics(uri, diagnostics)
        } catch (e: Exception) {
            logger.error("Error processing didOpen for $uri", e)
        }
    }

    /**
     * Document changed - re-parse and re-index.
     */
    override fun didChange(params: DidChangeTextDocumentParams) {
        val uri = params.textDocument.uri
        val changes = params.contentChanges

        if (changes.isEmpty()) return

        // Full document sync (TextDocumentSyncKind.Full)
        val content = changes[0].text

        logger.debug("didChange: $uri")
        index.setDocumentContent(uri, content)

        try {
            // Parse once — reuse for both indexing and diagnostics
            val parseResult = parser.parseWithErrors(uri, content)
            parseResult.smaliFile?.let { smaliFile ->
                index.indexFile(smaliFile)
                logger.debug("Re-indexed: $uri")
            } ?: logger.warn("Failed to re-parse: $uri")

            // Suppress semantic diagnostics during indexing for consistency with didOpen.
            // Without this, typing one character mid-indexing floods the file with
            // false "undefined-class" warnings that vanish once indexing completes.
            val diagnostics = if (indexingComplete) {
                diagnosticProvider.computeDiagnosticsFromParseResult(uri, parseResult)
            } else {
                diagnosticProvider.computeSyntaxDiagnosticsFromParseResult(parseResult)
            }
            publishDiagnostics(uri, diagnostics)
        } catch (e: Exception) {
            logger.error("Error processing didChange for $uri", e)
        }
    }
    
    /**
     * Document closed - keep in index (learned from V1).
     */
    override fun didClose(params: DidCloseTextDocumentParams) {
        val uri = params.textDocument.uri
        openFiles.remove(uri)
        index.removeDocumentContent(uri)
        logger.debug("didClose: $uri (keeping in index)")
        // Do NOT remove from index - learned from V1 bug
        // Removing breaks cross-file references (go-to-definition, find-references)
        // See ARCHITECTURE.md Bug #1: "Files removed from index too early"
        
        // Clear diagnostics for closed file
        publishDiagnostics(uri, emptyList())
    }
    
    /**
     * Document saved - no action needed (already indexed on change).
     */
    override fun didSave(params: DidSaveTextDocumentParams) {
        logger.debug("didSave: ${params.textDocument.uri}")
        // Already indexed on change, nothing to do
    }
    
    /**
     * Goto definition.
     */
    override fun definition(params: DefinitionParams): CompletableFuture<Either<MutableList<out Location>, MutableList<out LocationLink>>> {
        val uri = params.textDocument.uri
        val position = params.position
        
        logger.debug("definition: $uri at ${position.line}:${position.character}")
        
        return CompletableFuture.supplyAsync {
            try {
                val locations = definitionProvider.findDefinition(uri, position)
                Either.forLeft<MutableList<out Location>, MutableList<out LocationLink>>(locations.toMutableList())
            } catch (e: Exception) {
                logger.error("Error in definition for $uri", e)
                Either.forLeft(mutableListOf())
            }
        }
    }
    
    /**
     * Hover information.
     */
    override fun hover(params: HoverParams): CompletableFuture<Hover?> {
        val uri = params.textDocument.uri
        val position = params.position
        
        logger.debug("hover: $uri at ${position.line}:${position.character}")
        
        return CompletableFuture.supplyAsync {
            try {
                hoverProvider.provideHover(uri, position)
            } catch (e: Exception) {
                logger.error("Error in hover for $uri", e)
                null
            }
        }
    }
    
    /**
     * Find all references.
     */
    override fun references(params: ReferenceParams): CompletableFuture<MutableList<out Location>> {
        val uri = params.textDocument.uri
        val position = params.position
        val includeDeclaration = params.context.isIncludeDeclaration
        
        logger.debug("references: $uri at ${position.line}:${position.character}")
        
        return CompletableFuture.supplyAsync {
            try {
                referenceProvider.findReferences(uri, position, includeDeclaration).toMutableList()
            } catch (e: Exception) {
                logger.error("Error in references for $uri", e)
                mutableListOf()
            }
        }
    }
    
    /**
     * Document symbols (outline).
     */
    override fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<MutableList<Either<SymbolInformation, DocumentSymbol>>> {
        val uri = params.textDocument.uri
        
        logger.debug("documentSymbol: $uri")
        
        return CompletableFuture.supplyAsync {
            try {
                val file = index.findFileByUri(uri)
                if (file == null) {
                    return@supplyAsync mutableListOf<Either<SymbolInformation, DocumentSymbol>>()
                }
                
                val symbols = mutableListOf<Either<SymbolInformation, DocumentSymbol>>()
                
                // Class symbol
                val classSymbol = DocumentSymbol(
                    file.classDefinition.name,
                    SymbolKind.Class,
                    file.classDefinition.range,
                    file.classDefinition.range
                )
                classSymbol.children = mutableListOf()
                
                // Method symbols
                file.methods.forEach { method ->
                    val methodSymbol = DocumentSymbol(
                        "${method.name}${method.descriptor}",
                        SymbolKind.Method,
                        method.range,
                        method.range
                    )
                    classSymbol.children.add(methodSymbol)
                }
                
                // Field symbols
                file.fields.forEach { field ->
                    val fieldSymbol = DocumentSymbol(
                        "${field.name}: ${field.type}",
                        SymbolKind.Field,
                        field.range,
                        field.range
                    )
                    classSymbol.children.add(fieldSymbol)
                }
                
                symbols.add(Either.forRight(classSymbol))
                symbols
            } catch (e: Exception) {
                logger.error("Error in documentSymbol for $uri", e)
                mutableListOf()
            }
        }
    }

    /**
     * Prepare call hierarchy at cursor position.
     */
    override fun prepareCallHierarchy(params: CallHierarchyPrepareParams): CompletableFuture<MutableList<CallHierarchyItem>> {
        val uri = params.textDocument.uri
        val position = params.position

        return CompletableFuture.supplyAsync {
            try {
                callHierarchyProvider.prepare(uri, position).toMutableList()
            } catch (e: Exception) {
                logger.error("Error in prepareCallHierarchy for $uri", e)
                mutableListOf()
            }
        }
    }

    /**
     * Get incoming calls (callers) for a call hierarchy item.
     */
    override fun callHierarchyIncomingCalls(params: CallHierarchyIncomingCallsParams): CompletableFuture<MutableList<CallHierarchyIncomingCall>> {
        return CompletableFuture.supplyAsync {
            try {
                callHierarchyProvider.incomingCalls(params.item).toMutableList()
            } catch (e: Exception) {
                logger.error("Error in callHierarchyIncomingCalls", e)
                mutableListOf()
            }
        }
    }

    /**
     * Get outgoing calls (callees) for a call hierarchy item.
     */
    override fun callHierarchyOutgoingCalls(params: CallHierarchyOutgoingCallsParams): CompletableFuture<MutableList<CallHierarchyOutgoingCall>> {
        return CompletableFuture.supplyAsync {
            try {
                callHierarchyProvider.outgoingCalls(params.item).toMutableList()
            } catch (e: Exception) {
                logger.error("Error in callHierarchyOutgoingCalls", e)
                mutableListOf()
            }
        }
    }

    override fun prepareTypeHierarchy(params: TypeHierarchyPrepareParams): CompletableFuture<MutableList<TypeHierarchyItem>> {
        val uri = params.textDocument.uri
        val position = params.position

        return CompletableFuture.supplyAsync {
            try {
                typeHierarchyProvider.prepare(uri, position).toMutableList()
            } catch (e: Exception) {
                logger.error("Error in prepareTypeHierarchy for $uri", e)
                mutableListOf()
            }
        }
    }

    override fun typeHierarchySupertypes(params: TypeHierarchySupertypesParams): CompletableFuture<MutableList<TypeHierarchyItem>> {
        return CompletableFuture.supplyAsync {
            try {
                typeHierarchyProvider.supertypes(params.item).toMutableList()
            } catch (e: Exception) {
                logger.error("Error in typeHierarchySupertypes", e)
                mutableListOf()
            }
        }
    }

    override fun typeHierarchySubtypes(params: TypeHierarchySubtypesParams): CompletableFuture<MutableList<TypeHierarchyItem>> {
        return CompletableFuture.supplyAsync {
            try {
                typeHierarchyProvider.subtypes(params.item).toMutableList()
            } catch (e: Exception) {
                logger.error("Error in typeHierarchySubtypes", e)
                mutableListOf()
            }
        }
    }

    override fun codeLens(params: CodeLensParams): CompletableFuture<MutableList<out CodeLens>> {
        val uri = params.textDocument.uri

        return CompletableFuture.supplyAsync {
            try {
                codeLensProvider.provideCodeLenses(uri).toMutableList()
            } catch (e: Exception) {
                logger.error("Error in codeLens for $uri", e)
                mutableListOf()
            }
        }
    }

    override fun resolveCodeLens(params: CodeLens): CompletableFuture<CodeLens> {
        return CompletableFuture.supplyAsync {
            try {
                codeLensProvider.resolveCodeLens(params)
            } catch (e: Exception) {
                logger.error("Error in resolveCodeLens", e)
                params
            }
        }
    }

    override fun completion(params: CompletionParams): CompletableFuture<Either<MutableList<CompletionItem>, CompletionList>> {
        val uri = params.textDocument.uri
        val position = params.position

        return CompletableFuture.supplyAsync {
            try {
                val lineText = getLineFromBuffer(uri, position.line)

                val result = completionProvider.provideCompletions(uri, position, lineText)
                Either.forRight<MutableList<CompletionItem>, CompletionList>(result)
            } catch (e: Exception) {
                logger.error("Error in completion for $uri", e)
                Either.forRight(CompletionList(false, emptyList()))
            }
        }
    }

    override fun prepareRename(params: PrepareRenameParams): CompletableFuture<Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>> {
        val uri = params.textDocument.uri
        val position = params.position

        return CompletableFuture.supplyAsync {
            try {
                val result = renameProvider.prepareRename(uri, position)
                    ?: throw org.eclipse.lsp4j.jsonrpc.ResponseErrorException(
                        org.eclipse.lsp4j.jsonrpc.messages.ResponseError(
                            org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.InvalidRequest,
                            "Cannot rename this symbol",
                            null
                        )
                    )
                // Convert Either<Range, PrepareRenameResult> to Either3
                if (result.isLeft) {
                    Either3.forFirst(result.left)
                } else {
                    Either3.forSecond(result.right)
                }
            } catch (e: org.eclipse.lsp4j.jsonrpc.ResponseErrorException) {
                throw e
            } catch (e: Exception) {
                logger.error("Error in prepareRename for $uri", e)
                throw org.eclipse.lsp4j.jsonrpc.ResponseErrorException(
                    org.eclipse.lsp4j.jsonrpc.messages.ResponseError(
                        org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.InternalError,
                        "Rename preparation failed",
                        null
                    )
                )
            }
        }
    }

    override fun rename(params: RenameParams): CompletableFuture<WorkspaceEdit?> {
        val uri = params.textDocument.uri
        val position = params.position
        val newName = params.newName

        logger.debug("rename: $uri at ${position.line}:${position.character} -> $newName")

        return CompletableFuture.supplyAsync {
            try {
                renameProvider.rename(uri, position, newName)
            } catch (e: Exception) {
                logger.error("Error in rename for $uri", e)
                null
            }
        }
    }

    /**
     * Get line content from in-memory buffer (preferred) or disk (fallback).
     */
    internal fun getLineFromBuffer(uri: String, line: Int): String {
        return index.getLineContent(uri, line) ?: ""
    }
}
