package xyz.surendrajat.smalilsp.performance

import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.TestUtils
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import kotlin.system.measureTimeMillis
import kotlin.test.assertTrue

/**
 * Comprehensive performance benchmarking for both APKs.
 * Measures parse, index, and operation performance with percentiles.
 * 
 * Results are deterministic and reproducible (no random sampling).
 */
class ComprehensivePerformanceBenchmark {
    
    data class ParseMetrics(
        val totalFiles: Int,
        val successFiles: Int,
        val failedFiles: Int,
        val totalTimeMs: Long,
        val filesPerSec: Double,
        val avgTimeMs: Double,
        val p50Ms: Long,
        val p95Ms: Long,
        val p99Ms: Long,
        val maxMs: Long
    )
    
    data class IndexMetrics(
        val totalFiles: Int,
        val indexTimeMs: Long,
        val filesPerSec: Double,
        val classCount: Int,
        val methodCount: Int,
        val fieldCount: Int,
        val memoryUsedMB: Double
    )
    
    @Test
    fun `benchmark Mastodon APK - complete metrics`() {
        val apkDir = TestUtils.getMastodonApk()
        if (apkDir == null) {
            println("Skipping - Mastodon APK not available")
            return
        }
        
        println("\n" + "=".repeat(70))
        println("MASTODON APK PERFORMANCE BENCHMARK")
        println("=".repeat(70))
        
        // Phase 1: Parse Performance
        println("\n[1/3] Parse Performance...")
        val parseMetrics = measureParsePerformance(apkDir)
        printParseMetrics("Mastodon", parseMetrics)
        
        // Phase 2: Index Performance  
        println("\n[2/3] Index Performance...")
        val (index, indexMetrics) = measureIndexPerformance(apkDir)
        printIndexMetrics("Mastodon", indexMetrics)
        
        // Phase 3: Operation Performance
        println("\n[3/3] Operation Performance...")
        // TODO: Add operation benchmarks
        
        println("\n" + "=".repeat(70))
        println("MASTODON BENCHMARK COMPLETE")
        println("=".repeat(70))
    }
    
    @Test
    fun `benchmark ProtonMail APK - complete metrics`() {
        val apkDir = TestUtils.getProtonMailApk()
        if (apkDir == null) {
            println("Skipping - ProtonMail APK not available")
            return
        }
        
        println("\n" + "=".repeat(70))
        println("PROTONMAIL APK PERFORMANCE BENCHMARK")
        println("=".repeat(70))
        
        // Phase 1: Parse Performance
        println("\n[1/3] Parse Performance...")
        val parseMetrics = measureParsePerformance(apkDir)
        printParseMetrics("ProtonMail", parseMetrics)
        
        // Phase 2: Index Performance
        println("\n[2/3] Index Performance...")
        val (index, indexMetrics) = measureIndexPerformance(apkDir)
        printIndexMetrics("ProtonMail", indexMetrics)
        
        // Phase 3: Operation Performance
        println("\n[3/3] Operation Performance...")
        // TODO: Add operation benchmarks
        
        println("\n" + "=".repeat(70))
        println("PROTONMAIL BENCHMARK COMPLETE")
        println("=".repeat(70))
    }
    
    private fun measureParsePerformance(apkDir: java.io.File): ParseMetrics {
        val parser = SmaliParser()
        val files = apkDir.walkTopDown()
            .filter { it.extension == "smali" }
            .toList()
        
        val parseTimes = mutableListOf<Long>()
        var successCount = 0
        var failCount = 0
        
        val totalTime = measureTimeMillis {
            files.forEach { file ->
                try {
                    val parseTime = measureTimeMillis {
                        val content = file.readText()
                        val result = parser.parse(file.toURI().toString(), content)
                        if (result != null) {
                            successCount++
                        } else {
                            failCount++
                        }
                    }
                    parseTimes.add(parseTime)
                } catch (e: Exception) {
                    failCount++
                    parseTimes.add(0) // Failed parse
                }
            }
        }
        
        parseTimes.sort()
        val p50 = parseTimes[parseTimes.size / 2]
        val p95 = parseTimes[(parseTimes.size * 0.95).toInt()]
        val p99 = parseTimes[(parseTimes.size * 0.99).toInt()]
        val max = parseTimes.maxOrNull() ?: 0
        
        return ParseMetrics(
            totalFiles = files.size,
            successFiles = successCount,
            failedFiles = failCount,
            totalTimeMs = totalTime,
            filesPerSec = files.size * 1000.0 / totalTime,
            avgTimeMs = parseTimes.average(),
            p50Ms = p50,
            p95Ms = p95,
            p99Ms = p99,
            maxMs = max
        )
    }
    
    private fun measureIndexPerformance(apkDir: java.io.File): Pair<WorkspaceIndex, IndexMetrics> {
        val parser = SmaliParser()
        val index = WorkspaceIndex()
        val files = apkDir.walkTopDown()
            .filter { it.extension == "smali" }
            .toList()
        
        // Force GC before measurement
        System.gc()
        Thread.sleep(100)
        val memBefore = Runtime.getRuntime().let { 
            (it.totalMemory() - it.freeMemory()) / 1024.0 / 1024.0
        }
        
        val indexTime = measureTimeMillis {
            files.forEach { file ->
                try {
                    val content = file.readText()
                    val result = parser.parse(file.toURI().toString(), content)
                    if (result != null) {
                        index.indexFile(result)
                    }
                } catch (e: Exception) {
                    // Skip files that fail
                }
            }
        }
        
        val memAfter = Runtime.getRuntime().let {
            (it.totalMemory() - it.freeMemory()) / 1024.0 / 1024.0
        }
        
        val classCount = index.getAllClassNames().size
        val methodCount = index.getAllClassNames().sumOf { className ->
            index.findClass(className)?.methods?.size ?: 0
        }
        val fieldCount = index.getAllClassNames().sumOf { className ->
            index.findClass(className)?.fields?.size ?: 0
        }
        
        return Pair(index, IndexMetrics(
            totalFiles = files.size,
            indexTimeMs = indexTime,
            filesPerSec = files.size * 1000.0 / indexTime,
            classCount = classCount,
            methodCount = methodCount,
            fieldCount = fieldCount,
            memoryUsedMB = memAfter - memBefore
        ))
    }
    
    private fun printParseMetrics(apkName: String, metrics: ParseMetrics) {
        println("\n📊 $apkName Parse Performance:")
        println("   Total Files: ${metrics.totalFiles}")
        println("   Success: ${metrics.successFiles} (${String.format("%.2f", metrics.successFiles * 100.0 / metrics.totalFiles)}%)")
        println("   Failed: ${metrics.failedFiles}")
        println("   Total Time: ${metrics.totalTimeMs}ms (${String.format("%.2f", metrics.totalTimeMs / 1000.0)}s)")
        println("   Throughput: ${String.format("%.1f", metrics.filesPerSec)} files/sec")
        println("   Avg Time: ${String.format("%.2f", metrics.avgTimeMs)}ms per file")
        println("   p50: ${metrics.p50Ms}ms")
        println("   p95: ${metrics.p95Ms}ms")
        println("   p99: ${metrics.p99Ms}ms")
        println("   max: ${metrics.maxMs}ms")
    }
    
    private fun printIndexMetrics(apkName: String, metrics: IndexMetrics) {
        println("\n📊 $apkName Index Performance:")
        println("   Files Indexed: ${metrics.totalFiles}")
        println("   Index Time: ${metrics.indexTimeMs}ms (${String.format("%.2f", metrics.indexTimeMs / 1000.0)}s)")
        println("   Throughput: ${String.format("%.1f", metrics.filesPerSec)} files/sec")
        println("   Classes: ${metrics.classCount}")
        println("   Methods: ${metrics.methodCount}")
        println("   Fields: ${metrics.fieldCount}")
        println("   Memory Used: ${String.format("%.2f", metrics.memoryUsedMB)} MB")
    }
}
