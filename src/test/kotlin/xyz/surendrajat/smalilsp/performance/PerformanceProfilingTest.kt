package xyz.surendrajat.smalilsp.performance

import xyz.surendrajat.smalilsp.TestUtils

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.indexer.WorkspaceScanner
import java.io.File

/**
 * Performance profiling test to identify bottlenecks.
 * 
 * This test measures:
 * 1. File I/O time
 * 2. ANTLR parsing time
 * 3. Indexing time
 * 
 * Goal: Identify which operation is the bottleneck
 * Target: 1,340 files/sec (currently ~750-800)
 */
class PerformanceProfilingTest {
    
    @Test
    fun `profile ProtonMail indexing - identify bottleneck`() = runBlocking {
        println("\n" + "=".repeat(80))
        println("PERFORMANCE PROFILING - ProtonMail APK")
        println("=".repeat(80))
        println("Sampling 1000 files to identify bottleneck")
        println("Current: ~750-800 files/sec")
        println("Target: 1,340 files/sec (1.7x speedup needed)\n")
        
        val index = WorkspaceIndex()
        val scanner = WorkspaceScanner(index)
        val protonMailDir = TestUtils.getProtonMailApk() ?: run { println("Skipping - ProtonMail APK not available"); return@runBlocking }
        
        // Profile 1000 files
        val result = scanner.scanDirectoryWithProfiling(protonMailDir, sampleSize = 1000) { processed, total ->
            if (processed % 200 == 0 || processed == total) {
                println("Profiling: $processed/$total files")
            }
        }
        
        // Print breakdown
        result.printBreakdown()
        
        // Analysis
        println("ANALYSIS:")
        println("---------")
        
        val ioPercent = result.avgFileIOMs / result.avgTotalMs * 100
        val parsePercent = result.avgParseMs / result.avgTotalMs * 100
        val indexPercent = result.avgIndexMs / result.avgTotalMs * 100
        
        when {
            parsePercent > 60 -> {
                println("❌ BOTTLENECK: ANTLR Parsing (${parsePercent.toInt()}%)")
                println("\nOptimization strategies:")
                println("1. Enable ANTLR SLL prediction mode (faster but less accurate)")
                println("2. Optimize grammar rules (reduce backtracking)")
                println("3. Use ANTLR's two-stage parsing (SLL first, LL on error)")
                println("4. Consider lazy parsing (parse on-demand for hover/definition)")
            }
            ioPercent > 50 -> {
                println("❌ BOTTLENECK: File I/O (${ioPercent.toInt()}%)")
                println("\nOptimization strategies:")
                println("1. Use memory-mapped files (java.nio.MappedByteBuffer)")
                println("2. Increase I/O parallelism (more coroutines)")
                println("3. Pre-load files into memory before parsing")
                println("4. Use BufferedInputStream with larger buffer")
            }
            indexPercent > 40 -> {
                println("❌ BOTTLENECK: Indexing (${indexPercent.toInt()}%)")
                println("\nOptimization strategies:")
                println("1. Batch index updates (reduce ConcurrentHashMap contention)")
                println("2. Use faster data structures")
                println("3. Reduce string allocations in signature generation")
                println("4. Profile WorkspaceIndex.indexFile() specifically")
            }
            else -> {
                println("✅ No single bottleneck (balanced workload)")
                println("\nOptimization strategies:")
                println("1. Increase parallelism (more coroutines)")
                println("2. Tune chunk size in WorkspaceScanner")
                println("3. Profile with larger sample to confirm")
            }
        }
        
        println("\n" + "=".repeat(80))
        println("Next step: Apply optimization strategy and re-test")
        println("=".repeat(80) + "\n")
    }
    
    @Test
    fun `profile Mastodon indexing - validate across APKs`() = runBlocking {
        println("\n" + "=".repeat(80))
        println("VALIDATION PROFILE - Mastodon APK")
        println("=".repeat(80))
        
        val index = WorkspaceIndex()
        val scanner = WorkspaceScanner(index)
        val mastodonDir = TestUtils.getMastodonApk() ?: run { println("Skipping - Mastodon APK not available"); return@runBlocking }
        
        // Profile 1000 files
        val result = scanner.scanDirectoryWithProfiling(mastodonDir, sampleSize = 1000) { processed, total ->
            if (processed % 200 == 0 || processed == total) {
                println("Profiling: $processed/$total files")
            }
        }
        
        result.printBreakdown()
        
        println("NOTE: Results should be similar to ProtonMail profile")
        println("      If significantly different, need APK-specific tuning\n")
    }
    
    @Test
    fun `profile small sample for detailed analysis`() = runBlocking {
        println("\n" + "=".repeat(80))
        println("DETAILED PROFILE - 100 Files")
        println("=".repeat(80))
        println("Small sample for more stable timing measurements\n")
        
        val index = WorkspaceIndex()
        val scanner = WorkspaceScanner(index)
        val protonMailDir = TestUtils.getProtonMailApk() ?: run { println("Skipping - ProtonMail APK not available"); return@runBlocking }
        
        // Profile just 100 files for very stable measurements
        val result = scanner.scanDirectoryWithProfiling(protonMailDir, sampleSize = 100) { processed, total ->
            println("Profiling: $processed/$total files")
        }
        
        result.printBreakdown()
        
        println("This small sample provides stable timing for comparison")
        println("Use this as baseline before/after optimization\n")
    }
}
