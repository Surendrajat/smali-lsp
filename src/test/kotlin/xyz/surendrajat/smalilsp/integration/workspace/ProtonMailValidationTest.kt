package xyz.surendrajat.smalilsp.integration.workspace

import xyz.surendrajat.smalilsp.shared.TestUtils

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.BeforeAll
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.indexer.WorkspaceScanner
import java.io.File
import kotlin.test.assertTrue
import kotlin.system.measureTimeMillis

/**
 * Validation test using ProtonMail APK (18,249 files, 304MB).
 * 
 * Tests grammar fixes for:
 * - BUG-001/006: No hangs during indexing with parse errors
 * - BUG-003: Hex literals in array access (0x2000, etc.)
 * - BUG-004: IEEE 754 special values (Infinity, NaN)
 * - BUG-002: Generic types (if present in annotations)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProtonMailValidationTest {
    
    private lateinit var workspaceDir: File
    private lateinit var index: WorkspaceIndex
    private lateinit var scanner: WorkspaceScanner
    
    @BeforeAll
    fun setup() {
        val apk = TestUtils.getProtonMailApk()
        org.junit.jupiter.api.Assumptions.assumeTrue(apk != null, "ProtonMail APK not available, skipping")
        workspaceDir = apk!!
        assertTrue(workspaceDir.exists(), "ProtonMail decompiled directory must exist")
        
        index = WorkspaceIndex()
        scanner = WorkspaceScanner(index)
    }
    
    @Test
    fun `Should index ProtonMail without hanging`() = runBlocking {
        println("\n=== ProtonMail Validation Test ===")
        println("Files: 18,249 smali files (304MB)")
        println("Goal: No hangs, improved parse rate, <30s indexing\n")
        
        // Index with timeout - if it takes >70s, something is wrong
        var result: xyz.surendrajat.smalilsp.indexer.ScanResult? = null
        val indexTime = measureTimeMillis {
            result = scanner.scanDirectory(workspaceDir) { processed, total ->
                if (processed % 2000 == 0) {
                    println("Progress: $processed/$total files")
                }
            }
        }
        
        requireNotNull(result) { "Indexing returned null" }
        
        println("\n=== Results ===")
        println("Total files: ${result!!.filesProcessed}")
        println("Succeeded: ${result!!.filesSucceeded}")
        println("Failed: ${result!!.filesFailed}")
        println("Time: ${indexTime}ms (${indexTime/1000.0}s)")
        println("Rate: ${"%.1f".format(result!!.filesPerSecond)} files/sec")
        
        val successRate = (result!!.filesSucceeded * 100.0) / result!!.filesProcessed
        println("Success rate: ${"%.2f".format(successRate)}%")
        
        val stats = index.getStats()
        println("\nIndex stats:")
        println("  Classes: ${stats.classes}")
        println("  Methods: ${stats.methods}")
        println("  Fields: ${stats.fields}")
        
        // Assertions
        assertTrue(indexTime < 70_000, "Indexing should complete in <70s (took ${indexTime}ms)")
        assertTrue(result!!.filesSucceeded > 0, "Should successfully parse some files")
        assertTrue(successRate > 95.0, "Success rate should be >95% (was ${"%.2f".format(successRate)}%)")
        
        println("\n✅ ProtonMail validation PASSED")
        println("   - No hangs: ✓")
        println("   - Parse success rate: ${"%.2f".format(successRate)}%")
        println("   - Indexing time: ${indexTime/1000.0}s")
    }
}
