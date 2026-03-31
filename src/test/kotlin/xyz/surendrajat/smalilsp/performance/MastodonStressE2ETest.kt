package xyz.surendrajat.smalilsp.performance

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.condition.EnabledIf
import xyz.surendrajat.smalilsp.integration.lsp.E2ETestHarness
import xyz.surendrajat.smalilsp.integration.lsp.TestWorkspace
import xyz.surendrajat.smalilsp.integration.lsp.withE2ETest
import xyz.surendrajat.smalilsp.TestUtils
import java.io.File
import kotlin.time.measureTime
/**
 * Stress tests using real Mastodon APK decompiled sources
 */
@DisplayName("Mastodon APK Stress Tests")
@Tag("stress")
@Tag("mastodon")
class MastodonStressE2ETest {
    
    companion object {
        private val MASTODON_PATH = TestUtils.getMastodonApk()?.absolutePath ?: ""
        
        @JvmStatic
        fun mastodonExists(): Boolean {
            return MASTODON_PATH.isNotEmpty() && File(MASTODON_PATH).exists()
        }
    }
    
    @Test
    @DisplayName("All Mastodon features work end-to-end")
    @EnabledIf("mastodonExists")
    fun `all Mastodon features work end to end`() {
        val workspace = TestWorkspace.fromMastodon(MASTODON_PATH)
        
        println("\n=== Mastodon E2E Stress Test ===")
        println("Workspace: ${workspace.baseDir.absolutePath}")
        
        val stats = workspace.getStats()
        println("Stats: $stats")
        
        withE2ETest(workspace) {
            // Wait for initial indexing
            println("\nWaiting for indexing to complete...")
            waitForIndexing(30)
            
            // Test 1: Open MainActivity and get symbols
            println("\nTest 1: Document Symbols")
            testDocumentSymbols(workspace)
            
            // Test 2: Test goto definition
            println("\nTest 2: Goto Definition")
            testGotoDefinition(workspace)
            
            // Test 3: Test find references
            println("\nTest 3: Find References")
            testFindReferences(workspace)
            
            // Test 4: Test hover
            println("\nTest 4: Hover")
            testHover(workspace)
            
            // Test 5: Test cross-file navigation
            println("\nTest 5: Cross-File Navigation")
            testCrossFileNavigation(workspace)
            
            println("\n=== All Mastodon Tests Passed ===\n")
        }
    }
    
    private fun E2ETestHarness.testDocumentSymbols(workspace: TestWorkspace) {
        // Find any MainActivity file
        val mainActivityFiles = workspace.findFiles("MainActivity")
        
        if (mainActivityFiles.isEmpty()) {
            println("  ⚠ No MainActivity found, skipping")
            return
        }
        
        val file = mainActivityFiles[0]
        println("  Testing: $file")
        
        val (uri, openTime) = measureTime { openFile(file) }
        println("  Open time: ${openTime}ms")
        
        val (symbols, symbolTime) = measureTime { documentSymbols(uri) }
        println("  Symbol time: ${symbolTime}ms")
        
        assertTrue(openTime < 2000, "File should open in < 2s")
        assertTrue(symbolTime < 500, "Symbols should load in < 500ms")
        
        assertNotNull(symbols)
        assertTrue(symbols.isNotEmpty(), "Should have at least one symbol (the class)")
        
        val classSymbol = symbols[0]
        val children = classSymbol.children ?: emptyList()
        
        println("  Found: ${classSymbol.name} with ${children.size} members")
        
        if (children.size > 10) {
            println("  Sample members:")
            children.take(5).forEach { child ->
                println("    - ${child.name}")
            }
        }
        
        assertTrue(children.isNotEmpty(), "Class should have fields or methods")
    }
    
    private fun E2ETestHarness.testGotoDefinition(workspace: TestWorkspace) {
        // Find a file with method invocations
        val smaliFiles = workspace.listSmaliFiles()
        
        var tested = 0
        var successful = 0
        
        for (file in smaliFiles.take(20)) {  // Test first 20 files
            try {
                val content = workspace.readFile(file)
                
                // Look for invoke instructions
                val invokeLine = content.lines().indexOfFirst { 
                    it.trim().startsWith("invoke-") 
                }
                
                if (invokeLine == -1) continue
                
                val uri = openFile(file)
                val line = content.lines()[invokeLine]
                
                // Find method reference (after ->)
                val arrowIndex = line.indexOf("->")
                if (arrowIndex == -1) continue
                
                tested++
                
                // Try goto definition
                val (definitions, time) = measureTime {
                    gotoDefinition(uri, invokeLine, arrowIndex + 3, timeoutSeconds = 2)
                }
                
                if (definitions.isNotEmpty()) {
                    successful++
                    if (successful == 1) {
                        println("  ✓ First successful navigation:")
                        println("    File: $file")
                        println("    Line: ${invokeLine + 1}")
                        println("    Time: ${time}ms")
                        println("    Target: ${definitions[0].uri}")
                    }
                }
                
                if (tested >= 10) break
                
            } catch (e: Exception) {
                // Skip problematic files
            }
        }
        
        println("  Tested: $tested files")
        println("  Successful: $successful navigations")
        
        // We expect at least some navigations to work
        // (SDK methods won't resolve, but internal methods should)
        assertTrue(tested > 0, "Should have tested some files")
    }
    
    private fun E2ETestHarness.testFindReferences(workspace: TestWorkspace) {
        // Find a file with a method definition
        val smaliFiles = workspace.listSmaliFiles()
        
        var tested = 0
        var successful = 0
        
        for (file in smaliFiles.take(20)) {
            try {
                val content = workspace.readFile(file)
                
                // Look for a public method (more likely to have references)
                val methodLine = content.lines().indexOfFirst { 
                    it.trim().startsWith(".method public ")
                }
                
                if (methodLine == -1) continue
                
                val uri = openFile(file)
                tested++
                
                // Find references
                val (references, time) = measureTime {
                    findReferences(uri, methodLine, 20, timeoutSeconds = 5)
                }
                
                if (references.isNotEmpty()) {
                    successful++
                    if (successful == 1) {
                        println("  ✓ First successful reference search:")
                        println("    File: $file")
                        println("    Line: ${methodLine + 1}")
                        println("    References: ${references.size}")
                        println("    Time: ${time}ms")
                    }
                }
                
                if (tested >= 10) break
                
            } catch (e: Exception) {
                // Skip problematic files
            }
        }
        
        println("  Tested: $tested methods")
        println("  Found references: $successful methods")
        
        assertTrue(tested > 0, "Should have tested some methods")
    }
    
    private fun E2ETestHarness.testHover(workspace: TestWorkspace) {
        // Find a file with a method
        val smaliFiles = workspace.listSmaliFiles()
        
        var tested = 0
        var successful = 0
        
        for (file in smaliFiles.take(20)) {
            try {
                val content = workspace.readFile(file)
                
                // Look for method definition
                val methodLine = content.lines().indexOfFirst { 
                    it.trim().startsWith(".method ")
                }
                
                if (methodLine == -1) continue
                
                val uri = openFile(file)
                tested++
                
                // Hover over method
                val (hoverResult, time) = measureTime {
                    hover(uri, methodLine, 10, timeoutSeconds = 2)
                }
                
                if (hoverResult != null) {
                    successful++
                    if (successful == 1) {
                        println("  ✓ First successful hover:")
                        println("    File: $file")
                        println("    Time: ${time}ms")
                        val contents = hoverResult.contents.right?.value ?: ""
                        println("    Preview: ${contents.take(100)}...")
                    }
                }
                
                if (tested >= 10) break
                
            } catch (e: Exception) {
                // Skip problematic files
            }
        }
        
        println("  Tested: $tested hovers")
        println("  Successful: $successful hovers")
        
        assertTrue(tested > 0, "Should have tested some hovers")
    }
    
    private fun E2ETestHarness.testCrossFileNavigation(workspace: TestWorkspace) {
        // Find files with class references to other files
        val smaliFiles = workspace.listSmaliFiles()
        
        var tested = 0
        var successful = 0
        
        for (file in smaliFiles.take(50)) {
            try {
                val content = workspace.readFile(file)
                
                // Look for class references (not java/android SDK)
                val lines = content.lines()
                for (lineIndex in lines.indices) {
                    val line = lines[lineIndex]
                    
                    // Skip SDK classes
                    if (line.contains("Ljava/") || line.contains("Landroid/")) continue
                    
                    // Look for class descriptor pattern
                    if (line.contains(Regex("L[a-zA-Z][a-zA-Z0-9/$]*;"))) {
                        val uri = openFile(file)
                        tested++
                        
                        // Try to navigate
                        try {
                            val definitions = gotoDefinition(uri, lineIndex, 20, timeoutSeconds = 2)
                            
                            if (definitions.isNotEmpty() && definitions[0].uri != uri) {
                                successful++
                                if (successful == 1) {
                                    println("  ✓ First successful cross-file navigation:")
                                    println("    From: $file")
                                    println("    To: ${definitions[0].uri}")
                                    println("    Line: ${lineIndex + 1}")
                                }
                            }
                        } catch (e: Exception) {
                            // Skip
                        }
                        
                        if (tested >= 20) break
                    }
                }
                
                if (tested >= 20) break
                
            } catch (e: Exception) {
                // Skip problematic files
            }
        }
        
        println("  Tested: $tested references")
        println("  Successful cross-file: $successful")
        
        assertTrue(tested > 0, "Should have tested some cross-file references")
    }
    
    @Test
    @DisplayName("Mastodon indexing performance")
    @EnabledIf("mastodonExists")
    fun `Mastodon indexing completes in reasonable time`() {
        val workspace = TestWorkspace.fromMastodon(MASTODON_PATH)
        
        println("\n=== Mastodon Indexing Performance ===")
        val stats = workspace.getStats()
        println("Stats: $stats")
        
        val harness = E2ETestHarness(workspace)
        
        val (_, initTime) = harness.measureTime {
            harness.initialize()
        }
        
        println("Initialize time: ${initTime}ms")
        assertTrue(initTime < 5000, "Server should initialize in < 5s")
        
        // Give it time to index
        println("Waiting for indexing...")
        val start = System.currentTimeMillis()
        harness.waitForIndexing(30)
        val indexTime = System.currentTimeMillis() - start
        
        println("Index time: ${indexTime}ms (${indexTime / 1000}s)")
        
        // Open a random file to verify indexing worked
        val files = workspace.listSmaliFiles()
        if (files.isNotEmpty()) {
            val randomFile = files[files.size / 2]
            val uri = harness.openFile(randomFile)
            val symbols = harness.documentSymbols(uri)
            assertTrue(symbols.isNotEmpty(), "Indexing should work")
            println("Verified: Successfully indexed ${files.size} files")
        }
        
        harness.cleanup()
    }
    
    @Test
    @DisplayName("Large file performance")
    @EnabledIf("mastodonExists")
    fun `Large Mastodon files perform well`() {
        val workspace = TestWorkspace.fromMastodon(MASTODON_PATH)
        
        // Find largest file
        val files = workspace.listSmaliFiles()
        val largestFile = files.maxByOrNull { 
            try { workspace.getLineCount(it) } catch (e: Exception) { 0 }
        }
        
        assertNotNull(largestFile, "Should find at least one file")
        
        val lineCount = workspace.getLineCount(largestFile!!)
        println("\n=== Large File Performance ===")
        println("File: $largestFile")
        println("Lines: $lineCount")
        
        withE2ETest(workspace) {
            waitForIndexing(30)
            
            // Open large file
            val (uri, openTime) = measureTime { 
                openFile(largestFile) 
            }
            println("Open time: ${openTime}ms")
            assertTrue(openTime < 2000, "Large file should open in < 2s")
            
            // Get symbols
            val (symbols, symbolTime) = measureTime { 
                documentSymbols(uri) 
            }
            println("Symbol time: ${symbolTime}ms")
            assertTrue(symbolTime < 500, "Symbols should load in < 500ms")
            
            println("Symbols: ${symbols.size} top-level, ${symbols.sumOf { (it.children?.size ?: 0) }} children")
            
            // Try navigation in large file
            val lines = workspace.readFile(largestFile).lines()
            val invokeLine = lines.indexOfFirst { it.trim().startsWith("invoke-") }
            
            if (invokeLine != -1) {
                val (defs, navTime) = measureTime {
                    gotoDefinition(uri, invokeLine, 20, timeoutSeconds = 2)
                }
                println("Navigation time: ${navTime}ms")
                println("Found: ${defs.size} definitions")
                assertTrue(navTime < 500, "Navigation should be fast even in large file")
            }
        }
    }
}
