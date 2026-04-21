package xyz.surendrajat.smalilsp.integration.lsp

import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.WorkspaceFolder
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import java.io.File

import xyz.surendrajat.smalilsp.shared.E2ETestHarness
import xyz.surendrajat.smalilsp.shared.TestWorkspace
import xyz.surendrajat.smalilsp.shared.TestUtils
/**
 * End-to-end tests for Workspace Symbol feature.
 * 
 * Tests real LSP workspace/symbol requests on Mastodon APK.
 * 
 * Coverage:
 * - Basic query matching
 * - Fuzzy search
 * - Case insensitivity
 * - Result ordering
 * - Performance on large workspace
 * - Empty queries
 * - Multiple file search
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkspaceSymbolE2ETest {

    private val mastodonPath = TestUtils.getMastodonApk()
    private lateinit var harness: E2ETestHarness
    private lateinit var workspace: TestWorkspace
    
    @BeforeAll
    fun setup() {
        assumeTrue(mastodonPath?.exists() == true, "Mastodon APK not available — skipping WorkspaceSymbolE2ETest")

        workspace = TestWorkspace(mastodonPath!!)
        harness = E2ETestHarness(workspace)

        // Initialize with Mastodon workspace folder
        harness.initialize(
            workspaceFolders = listOf(
                WorkspaceFolder(workspace.baseDir.toURI().toString(), "mastodon")
            )
        )

        harness.waitForIndexing(5)
    }

    @AfterAll
    fun teardown() {
        if (mastodonPath != null && mastodonPath.exists() && ::harness.isInitialized) {
            harness.cleanup()
        }
    }
    
    /**
     * Test basic workspace symbol search.
     */
    @Test
    @Timeout(10)
    fun `workspace symbol search finds classes`() {
        // Search for "Activity" - should find many
        val results = harness.workspaceSymbols("Activity")
        
        // Should find activity classes
        assertTrue(results.isNotEmpty(), "Should find Activity classes")
        
        // Check that results contain Activity CLASS (not just method containing "Activity")
        val activityClass = results.find { it.kind == SymbolKind.Class && it.name.contains("Activity") }
        assertNotNull(activityClass, "Should find at least one Activity class")
        
        // Should be a class symbol
        assertEquals(SymbolKind.Class, activityClass?.kind)
    }
    
    /**
     * Test searching for methods.
     */
    @Test
    @Timeout(10)
    fun `workspace symbol search finds methods`() {
        // Search for "onCreate" - common method name
        val results = harness.workspaceSymbols("onCreate")
        
        // Should find onCreate methods
        assertTrue(results.isNotEmpty(), "Should find onCreate methods")
        
        // Check for method symbols
        val methodSymbol = results.find { it.kind == SymbolKind.Method }
        assertNotNull(methodSymbol, "Should find at least one method")
        assertTrue(methodSymbol?.name?.contains("onCreate") == true)
        
    }
    
    /**
     * Test case-insensitive search.
     */
    @Test
    @Timeout(10)
    fun `workspace symbol search is case insensitive`() {
        // Search with lowercase
        val lowerResults = harness.workspaceSymbols("activity")
        
        // Search with uppercase
        val upperResults = harness.workspaceSymbols("ACTIVITY")
        
        // Both should return results
        assertTrue(lowerResults.isNotEmpty())
        assertTrue(upperResults.isNotEmpty())
        
        // Should find similar symbols (case doesn't matter)
        assertTrue(lowerResults.any { it.name.contains("Activity", ignoreCase = true) })
        assertTrue(upperResults.any { it.name.contains("Activity", ignoreCase = true) })
        
    }
    
    /**
     * Test fuzzy matching.
     */
    @Test
    @Timeout(10)
    fun `workspace symbol search supports fuzzy matching`() {
        // Fuzzy search: "MA" could match "MainActivity"
        val results = harness.workspaceSymbols("MA")
        
        // Should find results
        assertTrue(results.isNotEmpty(), "Fuzzy search should find results")
        
    }
    
    /**
     * Test performance on large workspace (4000+ files).
     */
    @Test
    @Timeout(10)
    fun `workspace symbol search performs well on large workspace`() {
        val queries = listOf("Activity", "onCreate", "View", "String", "List")
        val elapsedTimes = mutableListOf<Long>()

        queries.forEach { query ->
            val start = System.currentTimeMillis()
            val results = harness.workspaceSymbols(query)
            val elapsed = System.currentTimeMillis() - start
            elapsedTimes.add(elapsed)

            println("Query '$query': ${results.size} results in ${elapsed}ms")

            assertTrue(results.size <= 500, "Should limit to 500 results")
        }

        val maxTime = elapsedTimes.maxOrNull() ?: 0L
        val avgTime = elapsedTimes.average()

        assertTrue(avgTime < 125.0, "Average search time should be < 125ms, was ${"%.1f".format(avgTime)}ms")
        assertTrue(maxTime < 250, "Slowest workspace symbol query should be < 250ms, was ${maxTime}ms")
    }
    
    /**
     * Test empty query behavior.
     */
    @Test
    @Timeout(10)
    fun `workspace symbol with empty query returns symbols`() {
        // Empty query
        val results = harness.workspaceSymbols("")
        
        // Should return symbols (empty query matches everything, up to limit)
        assertTrue(results.isNotEmpty(), "Empty query should return symbols")
        assertTrue(results.size <= 500, "Should respect result limit")
        
    }
    
    /**
     * Test result ordering (exact > prefix > contains > fuzzy).
     */
    @Test
    @Timeout(10)
    fun `workspace symbol results are ranked by relevance`() {
        // Search for a common term
        val results = harness.workspaceSymbols("View")
        
        assertTrue(results.isNotEmpty())
        
        // Top results should contain "View" prominently
        val topResults = results.take(10)
        assertTrue(topResults.any { it.name.contains("View", ignoreCase = true) })
        
    }
    
    /**
     * Test searching across multiple files.
     */
    @Test
    @Timeout(10)
    fun `workspace symbol searches across all indexed files`() {
        // Search for a common class name
        val results = harness.workspaceSymbols("Fragment")
        
        // Should find results from multiple files
        assertTrue(results.isNotEmpty())
        
        // Extract unique file URIs
        val uniqueFiles = results.map { it.location.uri }.distinct()
        
        // Should span multiple files (since Fragments are common in Android)
        assertTrue(uniqueFiles.size > 1, "Should find symbols from multiple files")
        
    }
    
    /**
     * Test no matches returns empty.
     */
    @Test
    @Timeout(10)
    fun `workspace symbol with no matches returns empty`() {
        // Search for something unlikely to exist
        val results = harness.workspaceSymbols("ThisClassDefinitelyDoesNotExist12345")
        
        // Should return empty
        assertEquals(0, results.size)
        
    }
}
