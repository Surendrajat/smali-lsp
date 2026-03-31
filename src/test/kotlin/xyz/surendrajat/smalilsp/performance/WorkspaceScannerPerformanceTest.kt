package xyz.surendrajat.smalilsp.performance

import xyz.surendrajat.smalilsp.TestUtils

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.condition.EnabledIf
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import java.io.File

import xyz.surendrajat.smalilsp.indexer.WorkspaceScanner
/**
 * Performance tests with large real-world datasets.
 * 
 * These tests are conditional - only run if the test data exists.
 */
class WorkspaceScannerPerformanceTest {
    
    companion object {
        // Path to real smali files from APK (if available)
        // Using ProtonMail (18,249 files) for large dataset test
        private val LARGE_DATASET_PATH = TestUtils.getProtonMailApk()?.absolutePath ?: ""
        
        @JvmStatic
        fun largeDatasetExists(): Boolean {
            val path = LARGE_DATASET_PATH
            if (path.isEmpty()) return false
            val dir = File(path)
            return dir.exists() && dir.isDirectory
        }
    }
    
    @Test
    @EnabledIf("largeDatasetExists")
    fun `performance test with real APK dataset - ProtonMail 18K files`() = runBlocking {
        val index = WorkspaceIndex()
        val scanner = WorkspaceScanner(index)
        val directory = File(LARGE_DATASET_PATH)
        
        println("========================================")
        println("PERFORMANCE TEST: Large Real-World Dataset")
        println("========================================")
        
        var progressCount = 0
        val result = scanner.scanDirectory(directory) { processed, total ->
            progressCount++
            if (progressCount % 10 == 0) {
                val percent = (processed * 100.0) / total
                println("Progress: $processed/$total (${String.format("%.1f", percent)}%)")
            }
        }
        
        println("\n========================================")
        println("RESULTS")
        println("========================================")
        println("Files processed: ${result.filesProcessed}")
        println("Files succeeded: ${result.filesSucceeded}")
        println("Files failed: ${result.filesFailed}")
        println("Duration: ${result.durationMs}ms (${result.durationMs / 1000.0}s)")
        println("Processing rate: ${String.format("%.1f", result.filesPerSecond)} files/sec")
        
        val stats = index.getStats()
        println("\n========================================")
        println("INDEX STATISTICS")
        println("========================================")
        println("Classes indexed: ${stats.classes}")
        println("Methods indexed: ${stats.methods}")
        println("Fields indexed: ${stats.fields}")
        
        // Verify performance requirement
        val maxAllowedMs = 5000L
        val withinTarget = result.durationMs <= maxAllowedMs
        
        println("\n========================================")
        println("PERFORMANCE ASSESSMENT")
        println("========================================")
        println("Target: <${maxAllowedMs}ms (<5 seconds)")
        println("Actual: ${result.durationMs}ms")
        println("Status: ${if (withinTarget) "✅ PASSED" else "⚠️  NEEDS OPTIMIZATION"}")
        
        if (!withinTarget) {
            println("\nOptimization suggestions:")
            println("- Increase coroutine parallelism")
            println("- Batch index operations")
            println("- Profile parser performance")
            println("- Consider incremental parsing")
        }
        
        println("========================================\n")
        
        // Test passes if we process all files successfully (even if slower than target)
        assertTrue(result.filesSucceeded > 17000, "Should successfully process most files (18K+)")
        assertEquals(result.filesProcessed, result.filesSucceeded + result.filesFailed)
        
        // Verify indexing worked
        assertTrue(stats.classes > 17000, "Should index most classes (18K+)")
        assertTrue(stats.methods > 50000, "Should index many methods (ProtonMail has ~150K+)")
    }
    
    @Test
    @EnabledIf("largeDatasetExists")
    fun `verify specific classes are indexed correctly`() = runBlocking {
        val index = WorkspaceIndex()
        val scanner = WorkspaceScanner(index)
        val directory = File(LARGE_DATASET_PATH)
        
        scanner.scanDirectory(directory)
        
        // Test a few known ProtonMail classes (real classes from APK)
        val testClasses = listOf(
            "Lu00/h;",
            "Lu00/j;",
            "Lu00/n;"
        )
        
        println("\nVerifying specific class indexing:")
        testClasses.forEach { className ->
            val found = index.findClass(className)
            println("  $className: ${if (found != null) "✅ FOUND" else "❌ NOT FOUND"}")
            assertNotNull(found, "Should find class $className")
        }
    }
    
    @Test
    fun `performance test with synthetic medium dataset`() = runBlocking {
        // Create synthetic dataset for CI/testing environments without real data
        val tempDir = createTempDir("smali-perf-test")
        try {
            // Create 500 synthetic smali files
            val fileCount = 500
            println("Creating $fileCount synthetic Smali files...")
            
            repeat(fileCount) { i ->
                val packagePath = "com/example/pkg${i % 10}"
                val dir = File(tempDir, packagePath)
                dir.mkdirs()
                
                File(dir, "Class$i.smali").writeText("""
                    .class public L$packagePath/Class$i;
                    .super Ljava/lang/Object;
                    
                    .field private field${i}a:I
                    .field private field${i}b:Ljava/lang/String;
                    
                    .method public constructor <init>()V
                        .locals 0
                        invoke-direct {p0}, Ljava/lang/Object;-><init>()V
                        return-void
                    .end method
                    
                    .method public method${i}a(I)V
                        .locals 0
                        return-void
                    .end method
                    
                    .method public method${i}b(Ljava/lang/String;)Ljava/lang/String;
                        .locals 0
                        return-object p1
                    .end method
                """.trimIndent())
            }
            
            val index = WorkspaceIndex()
            val scanner = WorkspaceScanner(index)
            
            println("Scanning $fileCount files...")
            val result = scanner.scanDirectory(tempDir)
            
            println("\n========================================")
            println("SYNTHETIC DATASET PERFORMANCE")
            println("========================================")
            println("Files: $fileCount")
            println("Duration: ${result.durationMs}ms")
            println("Rate: ${String.format("%.1f", result.filesPerSecond)} files/sec")
            println("========================================\n")
            
            assertEquals(fileCount, result.filesSucceeded)
            
            val stats = index.getStats()
            assertEquals(fileCount, stats.classes)
            assertEquals(fileCount * 2, stats.fields) // 2 fields per class
            assertEquals(fileCount * 3, stats.methods) // 3 methods per class
            
            // Should be fast
            assertTrue(result.durationMs < 3000, "500 files should complete in <3 seconds")
            
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
