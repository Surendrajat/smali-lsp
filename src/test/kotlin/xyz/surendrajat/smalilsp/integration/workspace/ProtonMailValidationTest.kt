package xyz.surendrajat.smalilsp.integration.workspace

import xyz.surendrajat.smalilsp.shared.PerformanceTestLock
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
    fun `Should index ProtonMail without hanging`() = PerformanceTestLock.withExclusiveLock("ProtonMailValidationTest") {
        runBlocking {
            println("\n=== ProtonMail Validation Test ===")
            println("Files: 18,249 smali files (304MB)")
            println("Goal: No hangs, stable full index, <30s indexing\n")

            var result: xyz.surendrajat.smalilsp.indexer.ScanResult? = null
            val indexTime = measureTimeMillis {
                result = scanner.scanDirectory(workspaceDir) { processed, total ->
                    if (processed % 2000 == 0) {
                        println("Progress: $processed/$total files")
                    }
                }
            }

            val scanResult = requireNotNull(result) { "Indexing returned null" }

            println("\n=== Results ===")
            println("Total files: ${scanResult.filesProcessed}")
            println("Succeeded: ${scanResult.filesSucceeded}")
            println("Failed: ${scanResult.filesFailed}")
            println("Time: ${indexTime}ms (${indexTime/1000.0}s)")
            println("Rate: ${"%.1f".format(scanResult.filesPerSecond)} files/sec")

            val successRate = (scanResult.filesSucceeded * 100.0) / scanResult.filesProcessed
            println("Success rate: ${"%.2f".format(successRate)}%")

            val stats = index.getStats()
            println("\nIndex stats:")
            println("  Classes: ${stats.classes}")
            println("  Methods: ${stats.methods}")
            println("  Fields: ${stats.fields}")

            assertTrue(indexTime < 30_000, "Indexing should complete in <30s (took ${indexTime}ms)")
            assertTrue(scanResult.filesSucceeded > 0, "Should successfully parse some files")
            assertTrue(successRate >= 99.9, "Success rate should stay near 100% (was ${"%.2f".format(successRate)}%)")
            assertTrue(stats.methods > 90_000, "Should index the expected ProtonMail method volume")

            println("\n✅ ProtonMail validation PASSED")
            println("   - No hangs: ✓")
            println("   - Parse success rate: ${"%.2f".format(successRate)}%")
            println("   - Indexing time: ${indexTime/1000.0}s")
        }
    }
}
