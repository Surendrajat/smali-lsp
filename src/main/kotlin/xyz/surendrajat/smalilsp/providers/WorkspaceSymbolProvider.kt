package xyz.surendrajat.smalilsp.providers

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import java.util.PriorityQueue
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
        if (normalizedQuery.isEmpty()) {
            return collectFirstSymbols()
        }

        val matches = TopSymbolMatches(MAX_RESULTS)

        index.getFilesView().forEach { file ->
            // Add class symbol - match on simple name OR full package path.
            // e.g. query "billingclient" should match "Lcom/android/billingclient/BillingClient;"
            val simpleName = extractSimpleName(file.classDefinition.name)
            val fullPath = file.classDefinition.name.removePrefix("L").removeSuffix(";").lowercase()
            val classMatch = matchSymbol(simpleName, normalizedQuery)
                ?: if (fullPath.contains(normalizedQuery)) 50 else null
            if (classMatch != null) {
                matches.offer(
                    ScoredSymbol(
                        name = file.classDefinition.name,
                        kind = SymbolKind.Class,
                        location = Location(file.uri, file.classDefinition.range),
                        containerName = null,
                        score = classMatch
                    )
                )
            }
            
            // Add method symbols - match ONLY on method name, not class+method
            file.methods.forEach { method ->
                val methodMatch = matchSymbol(method.name, normalizedQuery)
                if (methodMatch != null) {
                    // Display name includes class for context
                    val displayName = "${file.classDefinition.name}.${method.name}"
                    matches.offer(
                        ScoredSymbol(
                            name = displayName,
                            kind = SymbolKind.Method,
                            location = Location(file.uri, method.range),
                            containerName = file.classDefinition.name,
                            score = methodMatch
                        )
                    )
                }
            }
            
            // Add field symbols - match ONLY on field name, not class+field
            file.fields.forEach { field ->
                val fieldMatch = matchSymbol(field.name, normalizedQuery)
                if (fieldMatch != null) {
                    // Display name includes class for context
                    val displayName = "${file.classDefinition.name}.${field.name}"
                    matches.offer(
                        ScoredSymbol(
                            name = displayName,
                            kind = SymbolKind.Field,
                            location = Location(file.uri, field.range),
                            containerName = file.classDefinition.name,
                            score = fieldMatch
                        )
                    )
                }
            }
        }

        return matches.toSortedSymbolInformation()
    }

    private fun collectFirstSymbols(): List<SymbolInformation> {
        val results = ArrayList<SymbolInformation>(MAX_RESULTS)

        for (file in index.getFilesView()) {
            if (results.size >= MAX_RESULTS) break
            results.add(createClassSymbol(file))

            for (method in file.methods) {
                if (results.size >= MAX_RESULTS) break
                results.add(
                    SymbolInformation(
                        "${file.classDefinition.name}.${method.name}",
                        SymbolKind.Method,
                        Location(file.uri, method.range),
                        file.classDefinition.name
                    )
                )
            }

            for (field in file.fields) {
                if (results.size >= MAX_RESULTS) break
                results.add(
                    SymbolInformation(
                        "${file.classDefinition.name}.${field.name}",
                        SymbolKind.Field,
                        Location(file.uri, field.range),
                        file.classDefinition.name
                    )
                )
            }
        }

        return results
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

    private data class ScoredSymbol(
        val name: String,
        val kind: SymbolKind,
        val location: Location,
        val containerName: String?,
        val score: Int
    )

    private class TopSymbolMatches(limit: Int) {
        private val worstFirst = Comparator<ScoredSymbol> { left, right ->
            val scoreCompare = left.score.compareTo(right.score)
            if (scoreCompare != 0) {
                scoreCompare
            } else {
                right.name.compareTo(left.name)
            }
        }

        private val maxSize = limit
        private var buffered = ArrayList<ScoredSymbol>(minOf(limit, 64))
        private var heap: PriorityQueue<ScoredSymbol>? = null

        fun offer(symbol: ScoredSymbol) {
            val activeHeap = heap
            if (activeHeap == null) {
                buffered.add(symbol)
                if (buffered.size > maxSize) {
                    val newHeap = PriorityQueue<ScoredSymbol>(maxSize, worstFirst)
                    buffered.forEach { offerIntoHeap(newHeap, it) }
                    buffered = ArrayList(0)
                    heap = newHeap
                }
                return
            }

            offerIntoHeap(activeHeap, symbol)
        }

        fun toSortedSymbolInformation(): List<SymbolInformation> {
            val source = heap?.toList() ?: buffered
            return source
                .sortedWith(compareByDescending<ScoredSymbol> { it.score }.thenBy { it.name })
                .map { symbol ->
                    SymbolInformation(
                        symbol.name,
                        symbol.kind,
                        symbol.location,
                        symbol.containerName
                    )
                }
        }

        private fun offerIntoHeap(target: PriorityQueue<ScoredSymbol>, symbol: ScoredSymbol) {
            if (target.size < maxSize) {
                target.add(symbol)
                return
            }

            val worst = target.peek() ?: return
            if (worstFirst.compare(symbol, worst) > 0) {
                target.poll()
                target.add(symbol)
            }
        }
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
