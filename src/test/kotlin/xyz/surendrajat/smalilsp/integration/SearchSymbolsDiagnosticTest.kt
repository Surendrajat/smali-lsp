package xyz.surendrajat.smalilsp.integration

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.indexer.WorkspaceScanner
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.WorkspaceSymbolProvider
import xyz.surendrajat.smalilsp.shared.TestUtils
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * Diagnostic tests for search_symbols accuracy.
 * 
 * User reported: "search_symbols is severely broken: Only finding 22-30% of expected symbols"
 * 
 * This test suite investigates:
 * 1. Whether all indexed symbols are searchable
 * 2. Whether search patterns match expected results
 * 3. Whether the 100-result limit causes issues
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchSymbolsDiagnosticTest {
    
    private lateinit var index: WorkspaceIndex
    private lateinit var provider: WorkspaceSymbolProvider
    private var totalClasses = 0
    private var totalMethods = 0
    private var totalFields = 0
    
    /**
     * Index Mastodon APK for testing.
     */
    @BeforeAll
    fun setup() {
        val mastodonDir = TestUtils.getMastodonApk()
        assumeTrue(mastodonDir?.exists() == true, "Mastodon APK not available — skipping SearchSymbolsDiagnosticTest")
        
        val parser = SmaliParser()
        index = WorkspaceIndex()
        val scanner = WorkspaceScanner(index, parser)
        
        runBlocking {
            scanner.scanDirectory(mastodonDir!!)
        }
        
        totalClasses = index.getAllClassNames().size
        totalMethods = index.getAllFiles().sumOf { it.methods.size }
        totalFields = index.getAllFiles().sumOf { it.fields.size }
        assertTrue(totalClasses > 0, "Setup should index at least one class")
        
        provider = WorkspaceSymbolProvider(index)
        
        println("Indexed: $totalClasses classes, $totalMethods methods, $totalFields fields")
    }
    
    /**
     * Test 1: Verify that all class names are searchable.
     * 
     * IMPORTANT: In obfuscated code, many classes share the same simple name (e.g., "a", "b").
     * We cannot guarantee that a SPECIFIC class named "a" is returned when there are 100+ classes named "a".
     * This test verifies that searching for a simple name returns SOME class with that name.
     */
    @Test
    fun `all classes should be searchable by their simple name`() {
        // Get all class names
        val allClasses = index.getAllClassNames()
        
        // Group by simple name to understand the data
        val bySimpleName = allClasses.groupBy { className ->
            className.removePrefix("L").removeSuffix(";")
                .substringAfterLast("/")
                .substringAfterLast("$")
        }
        
        // Test unique simple names (to avoid testing "a" 100 times)
        var found = 0
        var notFound = 0
        val notFoundExamples = mutableListOf<String>()
        val tested = mutableSetOf<String>()
        
        allClasses.take(200).forEach { className ->
            val simpleName = className
                .removePrefix("L")
                .removeSuffix(";")
                .substringAfterLast("/")
                .substringAfterLast("$")
            
            // Skip if we already tested this simple name
            if (simpleName in tested) return@forEach
            tested.add(simpleName)
            
            // Search for it
            val results = provider.search(simpleName)
            
            // Check if ANY class with this simple name is in results
            val matchFound = results.any { result ->
                val resultSimpleName = result.name
                    .removePrefix("L")
                    .removeSuffix(";")
                    .substringAfterLast("/")
                    .substringAfterLast("$")
                resultSimpleName == simpleName
            }
            
            if (matchFound) {
                found++
            } else {
                notFound++
                if (notFoundExamples.size < 5) {
                    notFoundExamples.add("$simpleName (exists: ${bySimpleName[simpleName]?.size ?: 0} times)")
                }
            }
        }
        
        println("Classes found: $found/${found + notFound}")
        if (notFoundExamples.isNotEmpty()) {
            println("Examples not found: $notFoundExamples")
        }
        
        // At least 95% should be findable
        val accuracy = found * 100.0 / (found + notFound)
        assertTrue(accuracy >= 95, "Expected 95%+ accuracy, got $accuracy%")
    }
    
    /**
     * Test 2: Verify method search accuracy.
     * 
     * The display name is "${className}.${methodName}" so we need to extract the method name.
     */
    @Test
    fun `methods should be searchable by their name`() {
        // Common method names that should be found
        val testMethods = listOf("onCreate", "onResume", "onClick", "run", "toString", "hashCode")
        
        testMethods.forEach { methodName ->
            val results = provider.search(methodName)
            
            // Count how many methods with this EXACT name exist in the index
            val actualCount = index.getAllFiles().sumOf { file ->
                file.methods.count { it.name.equals(methodName, ignoreCase = true) }
            }
            
            // Count how many METHOD results we found with exact name match
            // (filter by SymbolKind.Method to exclude classes/fields)
            // Display name format: "Lcom/example/Class;.methodName"
            val foundCount = results.count { result ->
                if (result.kind != org.eclipse.lsp4j.SymbolKind.Method) return@count false
                val displayName = result.name
                val dotIndex = displayName.lastIndexOf('.')
                if (dotIndex > 0) {
                    val extractedMethodName = displayName.substring(dotIndex + 1)
                    extractedMethodName.equals(methodName, ignoreCase = true)
                } else {
                    false
                }
            }
            
            println("$methodName: found $foundCount, actual $actualCount")
            
            // If there are <= MAX_RESULTS matches, we should find all of them
            // If there are > MAX_RESULTS, we should find at least MAX_RESULTS
            if (actualCount <= 500) {
                assertEquals(actualCount, foundCount, "Should find all $methodName methods")
            } else {
                assertTrue(foundCount >= 500, "Should find at least 500 $methodName methods")
            }
        }
    }
    
    /**
     * Test 3: Verify empty query returns all symbols (up to limit).
     */
    @Test
    fun `empty query returns symbols up to limit`() {
        val results = provider.search("")
        
        // Empty query should return MAX_RESULTS symbols (now 500)
        assertEquals(500, results.size, "Empty query should return 500 symbols")
    }
    
    /**
     * Test 4: Verify total count vs returned results.
     */
    @Test
    fun `search returns count and truncated flag correctly`() {
        val results = provider.search("Activity")
        
        // Count actual matches (classes matching by simple name OR package path, plus methods/fields by name)
        val actualClassMatches = index.getAllClassNames().count { className ->
            val simpleName = className.removePrefix("L").removeSuffix(";")
                .substringAfterLast("/").substringAfterLast("$")
            val fullPath = className.removePrefix("L").removeSuffix(";")
            simpleName.contains("Activity", ignoreCase = true) || fullPath.contains("Activity", ignoreCase = true)
        }
        val actualMethodMatches = index.getAllFiles().sumOf { file ->
            file.methods.count { it.name.contains("Activity", ignoreCase = true) }
        }
        val actualFieldMatches = index.getAllFiles().sumOf { file ->
            file.fields.count { it.name.contains("Activity", ignoreCase = true) }
        }
        val actualMatches = actualClassMatches + actualMethodMatches + actualFieldMatches
        
        println("Activity search: returned ${results.size}, actual $actualMatches (classes: $actualClassMatches, methods: $actualMethodMatches, fields: $actualFieldMatches)")
        
        if (actualMatches > 500) {
            assertEquals(500, results.size, "Should truncate to 500 when more matches exist")
        } else {
            assertEquals(actualMatches, results.size, "Should return all matches when <= 500")
        }
    }
    
    /**
     * Test 5: Diagnose search accuracy on all method names.
     * 
     * Tests that searching for a method name returns methods with that exact name.
     * This is the key accuracy test.
     */
    @Test
    fun `diagnose search accuracy on all method names`() {
        // Collect all unique method names
        val allMethodNames = mutableSetOf<String>()
        index.getAllFiles().forEach { file ->
            file.methods.forEach { method ->
                allMethodNames.add(method.name)
            }
        }
        
        println("Unique method names: ${allMethodNames.size}")
        
        // Test each method name (first 50 unique names)
        var totalSearched = 0
        var totalFound = 0
        var totalExpected = 0
        val misses = mutableListOf<String>()
        
        allMethodNames.take(50).forEach { methodName ->
            val results = provider.search(methodName)
            
            // How many methods have this exact name?
            val expected = index.getAllFiles().sumOf { file ->
                file.methods.count { it.name == methodName }
            }
            
            // How many METHOD results did we find with EXACT name match?
            // Filter by SymbolKind.Method and extract name from display
            // Display name format: "Lcom/example/Class;.methodName"
            val found = results.count { result ->
                if (result.kind != org.eclipse.lsp4j.SymbolKind.Method) return@count false
                val displayName = result.name
                val dotIndex = displayName.lastIndexOf('.')
                if (dotIndex > 0) {
                    val extractedMethodName = displayName.substring(dotIndex + 1)
                    extractedMethodName == methodName
                } else {
                    false
                }
            }
            
            totalSearched++
            val foundCapped = minOf(found, expected, 500)
            val expectedCapped = minOf(expected, 500)
            totalFound += foundCapped
            totalExpected += expectedCapped
            
            // Report significant discrepancies
            if (expected > 0 && found < expected && (expected - found) > 1) {
                val missRate = ((expected - found) * 100.0 / expected)
                if (missRate > 10) {
                    misses.add("$methodName: found $found, expected $expected (missed ${expected - found})")
                }
            }
        }
        
        // Print worst misses
        if (misses.isNotEmpty()) {
            println("Significant misses:")
            misses.take(10).forEach { println("  $it") }
        }
        
        val accuracy = if (totalExpected > 0) totalFound * 100.0 / totalExpected else 100.0
        println("Overall accuracy: $accuracy% ($totalFound/$totalExpected)")
        
        // Target: 80% accuracy (realistic for obfuscated code with many short names)
        assertTrue(accuracy >= 80, "Accuracy should be >= 80%, got $accuracy%")
    }
}
