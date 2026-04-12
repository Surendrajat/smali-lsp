package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import xyz.surendrajat.smalilsp.core.SmaliFile
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.util.ClassUtils

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
 * 6. Return up to 500 results for performance
 */
class WorkspaceSymbolProvider(
    private val index: WorkspaceIndex
) {
    companion object {
        // Increased from 100 to allow more results while still being performant
        private const val MAX_RESULTS = 500
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
            // Add class symbol - match on simple name OR full package path.
            // e.g. query "billingclient" should match "Lcom/android/billingclient/BillingClient;"
            val simpleName = extractSimpleName(file.classDefinition.name)
            val fullPath = file.classDefinition.name.removePrefix("L").removeSuffix(";").lowercase()
            val classMatch = matchSymbol(simpleName, normalizedQuery)
                ?: if (fullPath.contains(normalizedQuery)) 50 else null
            if (classMatch != null) {
                matches.add(
                    createClassSymbol(file) to classMatch
                )
            }
            
            // Add method symbols - match ONLY on method name, not class+method
            file.methods.forEach { method ->
                val methodMatch = matchSymbol(method.name, normalizedQuery)
                if (methodMatch != null) {
                    // Display name includes class for context
                    val displayName = "${file.classDefinition.name}.${method.name}"
                    matches.add(
                        SymbolInformation(
                            displayName,
                            SymbolKind.Method,
                            Location(file.uri, method.range),
                            file.classDefinition.name
                        ) to methodMatch
                    )
                }
            }
            
            // Add field symbols - match ONLY on field name, not class+field
            file.fields.forEach { field ->
                val fieldMatch = matchSymbol(field.name, normalizedQuery)
                if (fieldMatch != null) {
                    // Display name includes class for context
                    val displayName = "${file.classDefinition.name}.${field.name}"
                    matches.add(
                        SymbolInformation(
                            displayName,
                            SymbolKind.Field,
                            Location(file.uri, field.range),
                            file.classDefinition.name
                        ) to fieldMatch
                    )
                }
            }
        }

        // Search string literals (only when query is 2+ chars to avoid noise)
        if (normalizedQuery.length >= 2) {
            val stringResults = index.searchStrings(normalizedQuery, 100)
            stringResults.forEach { result ->
                val truncated = if (result.value.length > 60) result.value.take(60) + "..." else result.value
                val className = index.findClassNameByUri(result.location.uri) ?: "unknown"
                matches.add(
                    SymbolInformation(
                        "\"$truncated\"",
                        SymbolKind.String,
                        result.location,
                        className
                    ) to 50  // Lower than class/method matches
                )
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
     * - 1000: Exact match (case-insensitive)
     * - 500 + length bonus: Prefix match (shorter symbols score higher)
     * - 100 + length bonus: Contains match (shorter symbols score higher)
     * - 10: Fuzzy match (only for queries >= 3 chars)
     * - null: No match
     * 
     * The length bonus ensures shorter symbol names rank higher:
     * - Searching "q" returns class "q" before "queryString"
     */
    private fun matchSymbol(symbolName: String, query: String): Int? {
        if (query.isEmpty()) return 10 // Everything matches empty query
        
        val normalizedSymbol = symbolName.lowercase()
        
        // Calculate length bonus: shorter names get higher scores
        // Max bonus of 100, decreases with length
        val lengthBonus = maxOf(0, 100 - normalizedSymbol.length * 5)
        
        // Exact match - highest priority
        if (normalizedSymbol == query) return 1000 + lengthBonus
        
        // Prefix match - second priority
        if (normalizedSymbol.startsWith(query)) return 500 + lengthBonus
        
        // Contains match - third priority
        if (normalizedSymbol.contains(query)) return 100 + lengthBonus
        
        // Fuzzy match: only for longer queries to avoid noise
        // Single-letter queries like "q" should NOT fuzzy match "request"
        if (query.length >= 3 && fuzzyMatch(normalizedSymbol, query)) return 10
        
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
    
    /**
     * Extract simple class name from full Smali type.
     * 
     * Examples:
     * - "Lcom/example/MyClass;" -> "MyClass"
     * - "Lcom/example/Outer$Inner;" -> "Inner"
     * - "La/b/c/q;" -> "q"
     */
    private fun extractSimpleName(fullName: String): String {
        return ClassUtils.extractSimpleName(fullName)
    }
}
