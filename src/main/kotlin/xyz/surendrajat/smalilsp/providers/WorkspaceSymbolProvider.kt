package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import xyz.surendrajat.smalilsp.core.SmaliFile
import xyz.surendrajat.smalilsp.index.WorkspaceIndex

/**
 * Provides workspace-wide symbol search.
 * 
 * Features:
 * - Fuzzy matching on symbol names
 * - Case-insensitive search
 * - Searches classes, methods, and fields
 * - Fast performance (< 500ms for 4000+ files)
 * 
 * LSP Request: workspace/symbol
 * 
 * Search Algorithm:
 * 1. If query is empty, return all symbols (up to limit)
 * 2. Normalize query to lowercase for case-insensitive matching
 * 3. Check if symbol name contains query substring
 * 4. Support fuzzy matching: "FBAdapter" matches "FeedbackAdapter"
 * 5. Rank results by match quality (exact > prefix > contains)
 * 6. Return up to 100 results for performance
 */
class WorkspaceSymbolProvider(
    private val index: WorkspaceIndex
) {
    companion object {
        private const val MAX_RESULTS = 100
    }
    
    /**
     * Search workspace for symbols matching query.
     * 
     * @param query Search string (case-insensitive, supports fuzzy matching)
     * @return List of matching symbols, ranked by relevance
     */
    fun search(query: String): List<SymbolInformation> {
        val normalizedQuery = query.lowercase().trim()
        
        // Get all indexed files
        val allFiles = index.getAllFiles()
        
        // Collect all symbols with their match scores
        val matches = mutableListOf<Pair<SymbolInformation, Int>>()
        
        allFiles.forEach { file ->
            // Add class symbol
            val classMatch = matchSymbol(file.classDefinition.name, normalizedQuery)
            if (classMatch != null) {
                matches.add(
                    createClassSymbol(file) to classMatch
                )
            }
            
            // Add method symbols
            file.methods.forEach { method ->
                val methodName = "${file.classDefinition.name}.${method.name}"
                val methodMatch = matchSymbol(methodName, normalizedQuery)
                if (methodMatch != null) {
                    matches.add(
                        SymbolInformation(
                            methodName,
                            SymbolKind.Method,
                            Location(file.uri, method.range),
                            file.classDefinition.name
                        ) to methodMatch
                    )
                }
            }
            
            // Add field symbols
            file.fields.forEach { field ->
                val fieldName = "${file.classDefinition.name}.${field.name}"
                val fieldMatch = matchSymbol(fieldName, normalizedQuery)
                if (fieldMatch != null) {
                    matches.add(
                        SymbolInformation(
                            fieldName,
                            SymbolKind.Field,
                            Location(file.uri, field.range),
                            file.classDefinition.name
                        ) to fieldMatch
                    )
                }
            }
        }
        
        // Sort by match score (higher is better), then by name
        return matches
            .sortedWith(compareByDescending<Pair<SymbolInformation, Int>> { it.second }
                .thenBy { it.first.name })
            .take(MAX_RESULTS)
            .map { it.first }
    }
    
    /**
     * Match a symbol name against a query.
     * 
     * Returns match score (higher is better):
     * - 100: Exact match
     * - 50: Prefix match
     * - 25: Contains match
     * - 10: Fuzzy match (query letters in order)
     * - null: No match
     */
    private fun matchSymbol(symbolName: String, query: String): Int? {
        if (query.isEmpty()) return 10 // Everything matches empty query
        
        val normalizedSymbol = symbolName.lowercase()
        
        // Exact match
        if (normalizedSymbol == query) return 100
        
        // Prefix match (best for most IDE searches)
        if (normalizedSymbol.startsWith(query)) return 50
        
        // Contains match
        if (normalizedSymbol.contains(query)) return 25
        
        // Fuzzy match: all query letters in order
        if (fuzzyMatch(normalizedSymbol, query)) return 10
        
        return null
    }
    
    /**
     * Fuzzy match: check if all query letters appear in order in symbol.
     * 
     * Examples:
     * - "FBA" matches "FeedBackAdapter"
     * - "onCrt" matches "onCreate"
     * - "str" matches "StringBuilder"
     */
    private fun fuzzyMatch(symbol: String, query: String): Boolean {
        var symbolIdx = 0
        var queryIdx = 0
        
        while (symbolIdx < symbol.length && queryIdx < query.length) {
            if (symbol[symbolIdx] == query[queryIdx]) {
                queryIdx++
            }
            symbolIdx++
        }
        
        return queryIdx == query.length
    }
    
    /**
     * Create a SymbolInformation for a class.
     */
    private fun createClassSymbol(file: SmaliFile): SymbolInformation {
        return SymbolInformation(
            file.classDefinition.name,
            SymbolKind.Class,
            Location(file.uri, file.classDefinition.range)
        )
    }
}
