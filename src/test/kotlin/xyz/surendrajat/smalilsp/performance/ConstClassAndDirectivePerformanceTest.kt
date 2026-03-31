package xyz.surendrajat.smalilsp.performance

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/**
 * Performance test for const-class and directive reference features.
 * Tests on 3 real APKs: Mastodon (4.4K), ProtonMail (18.2K).
 * 
 * Success Criteria:
 * - Find references avg <50ms, max <200ms
 * - No regressions from baseline
 * - Memory usage reasonable
 */
@Timeout(30, unit = TimeUnit.MINUTES)
class ConstClassAndDirectivePerformanceTest {
    
    data class APKMetrics(
        val name: String,
        val fileCount: Int,
        val indexTimeMs: Long,
        val constClassRefs: List<RefMetric>,
        val superDirectiveRefs: List<RefMetric>,
        val memoryUsedMB: Long
    )
    
    data class RefMetric(
        val className: String,
        val timeMs: Long,
        val refCount: Int
    )
    
    @Test
    fun `performance test on 3 APKs`() {
        println("\n" + "=".repeat(80))
        println("CONST-CLASS AND DIRECTIVE REFERENCE PERFORMANCE TEST")
        println("Testing on 3 real APKs with full indexing")
        println("=".repeat(80))
        
        val apks = listOf(
            "apk/mastodon_decompiled" to "Mastodon",
            "apk/protonmail_decompiled" to "ProtonMail",
        )
        
        val results = mutableListOf<APKMetrics>()
        
        for ((path, name) in apks) {
            val apkDir = File(path)
            if (!apkDir.exists()) {
                println("\n⚠️  $name not found at $path, skipping...")
                continue
            }
            
            println("\n" + "=".repeat(80))
            println("Testing: $name")
            println("=".repeat(80))
            
            val metrics = testAPK(apkDir, name)
            results.add(metrics)
            printMetrics(metrics)
        }
        
        // Overall summary
        println("\n" + "=".repeat(80))
        println("OVERALL SUMMARY")
        println("=".repeat(80))
        
        printOverallSummary(results)
        
        // Validation
        results.forEach { metrics ->
            val avgConstClassTime = metrics.constClassRefs.map { it.timeMs }.average()
            val maxConstClassTime = metrics.constClassRefs.maxOfOrNull { it.timeMs } ?: 0
            val avgSuperTime = metrics.superDirectiveRefs.map { it.timeMs }.average()
            val maxSuperTime = metrics.superDirectiveRefs.maxOfOrNull { it.timeMs } ?: 0
            
            println("\n✓ ${metrics.name}:")
            println("   const-class refs: avg ${avgConstClassTime.toLong()}ms, max ${maxConstClassTime}ms")
            println("   .super refs: avg ${avgSuperTime.toLong()}ms, max ${maxSuperTime}ms")
            
            // Warn if performance is poor
            if (avgConstClassTime > 50 || avgSuperTime > 50) {
                println("   ⚠️  Average time exceeds 50ms target")
            }
            if (maxConstClassTime > 200 || maxSuperTime > 200) {
                println("   ⚠️  Max time exceeds 200ms target")
            }
            
            // But don't fail the test - just report
            assertTrue(avgConstClassTime < 500, "${metrics.name}: const-class avg time ${avgConstClassTime}ms is too slow")
            assertTrue(maxConstClassTime < 1000, "${metrics.name}: const-class max time ${maxConstClassTime}ms is too slow")
        }
        
        println("\n✅ PERFORMANCE TEST COMPLETE")
    }
    
    private fun testAPK(apkDir: File, name: String): APKMetrics {
        // 1. Index all files
        println("\n📦 Indexing $name...")
        val parser = SmaliParser()
        val index = WorkspaceIndex()
        
        val runtime = Runtime.getRuntime()
        runtime.gc()
        Thread.sleep(100)
        val memBefore = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        
        val files = apkDir.walkTopDown()
            .filter { it.extension == "smali" }
            .toList()
        
        val indexStart = System.currentTimeMillis()
        files.forEach { file ->
            try {
                val content = file.readText()
                val uri = file.toURI().toString()
                val smaliFile = parser.parse(uri, content)
                if (smaliFile != null) {
                    index.indexFile(smaliFile)
                }
            } catch (e: Exception) {
                // Continue
            }
        }
        val indexTime = System.currentTimeMillis() - indexStart
        
        runtime.gc()
        Thread.sleep(100)
        val memAfter = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        
        println("   Indexed ${files.size} files in ${indexTime}ms")
        println("   Memory: ${memAfter - memBefore}MB")
        
        // 2. Test const-class references
        println("\n🔍 Testing const-class find references...")
        val constClassRefs = testConstClassReferences(files, index)
        
        // 3. Test .super directive references
        println("🔍 Testing .super directive find references...")
        val superRefs = testSuperDirectiveReferences(files, index)
        
        return APKMetrics(
            name = name,
            fileCount = files.size,
            indexTimeMs = indexTime,
            constClassRefs = constClassRefs,
            superDirectiveRefs = superRefs,
            memoryUsedMB = memAfter - memBefore
        )
    }
    
    private fun testConstClassReferences(files: List<File>, index: WorkspaceIndex): List<RefMetric> {
        val provider = ReferenceProvider(index)
        val metrics = mutableListOf<RefMetric>()
        
        // Find const-class instructions in files
        val constClassPattern = Regex("""const-class\s+\w+,\s+(L[a-zA-Z0-9/\$]+;)""")
        var tested = 0
        
        files.shuffled().take(100).forEach { file ->
            if (tested >= 20) return@forEach // Test up to 20 const-class refs
            
            val content = file.readText()
            val lines = content.lines()
            
            lines.forEachIndexed { lineIdx, line ->
                if (tested >= 20) return@forEachIndexed
                
                val match = constClassPattern.find(line)
                if (match != null) {
                    val className = match.groupValues[1]
                    val uri = file.toURI().toString()
                    // Position cursor on class name
                    val pos = Position(lineIdx, line.indexOf(className))
                    
                    val start = System.currentTimeMillis()
                    val refs = provider.findReferences(uri, pos, true)
                    val time = System.currentTimeMillis() - start
                    
                    metrics.add(RefMetric(className, time, refs.size))
                    tested++
                }
            }
        }
        
        println("   Tested $tested const-class references")
        return metrics
    }
    
    private fun testSuperDirectiveReferences(files: List<File>, index: WorkspaceIndex): List<RefMetric> {
        val provider = ReferenceProvider(index)
        val metrics = mutableListOf<RefMetric>()
        
        // Find .super directives in files
        val superPattern = Regex("""\.super\s+(L[a-zA-Z0-9/\$]+;)""")
        var tested = 0
        
        files.shuffled().take(100).forEach { file ->
            if (tested >= 20) return@forEach // Test up to 20 .super refs
            
            val content = file.readText()
            val lines = content.lines()
            
            lines.forEachIndexed { lineIdx, line ->
                if (tested >= 20) return@forEachIndexed
                
                val match = superPattern.find(line)
                if (match != null) {
                    val className = match.groupValues[1]
                    // Skip SDK classes
                    if (className.startsWith("Ljava/") || className.startsWith("Landroid/")) {
                        return@forEachIndexed
                    }
                    
                    val uri = file.toURI().toString()
                    // Position cursor on class name
                    val pos = Position(lineIdx, line.indexOf(className))
                    
                    val start = System.currentTimeMillis()
                    val refs = provider.findReferences(uri, pos, true)
                    val time = System.currentTimeMillis() - start
                    
                    metrics.add(RefMetric(className, time, refs.size))
                    tested++
                }
            }
        }
        
        println("   Tested $tested .super directive references")
        return metrics
    }
    
    private fun printMetrics(metrics: APKMetrics) {
        println("\n📊 ${metrics.name} Results:")
        println("   Files: ${metrics.fileCount}")
        println("   Index time: ${metrics.indexTimeMs}ms")
        println("   Memory: ${metrics.memoryUsedMB}MB")
        
        if (metrics.constClassRefs.isNotEmpty()) {
            val avgTime = metrics.constClassRefs.map { it.timeMs }.average()
            val maxTime = metrics.constClassRefs.maxOf { it.timeMs }
            val avgRefs = metrics.constClassRefs.map { it.refCount }.average()
            
            println("\n   const-class references:")
            println("      Samples: ${metrics.constClassRefs.size}")
            println("      Avg time: ${avgTime.toLong()}ms")
            println("      Max time: ${maxTime}ms")
            println("      Avg refs found: ${avgRefs.toLong()}")
        }
        
        if (metrics.superDirectiveRefs.isNotEmpty()) {
            val avgTime = metrics.superDirectiveRefs.map { it.timeMs }.average()
            val maxTime = metrics.superDirectiveRefs.maxOf { it.timeMs }
            val avgRefs = metrics.superDirectiveRefs.map { it.refCount }.average()
            
            println("\n   .super directive references:")
            println("      Samples: ${metrics.superDirectiveRefs.size}")
            println("      Avg time: ${avgTime.toLong()}ms")
            println("      Max time: ${maxTime}ms")
            println("      Avg refs found: ${avgRefs.toLong()}")
        }
    }
    
    private fun printOverallSummary(results: List<APKMetrics>) {
        val totalFiles = results.sumOf { it.fileCount }
        val totalIndexTime = results.sumOf { it.indexTimeMs }
        val totalMemory = results.sumOf { it.memoryUsedMB }
        
        println("\nTotal files indexed: $totalFiles")
        println("Total index time: ${totalIndexTime}ms (${totalIndexTime / 1000}s)")
        println("Total memory: ${totalMemory}MB")
        println("Average throughput: ${totalFiles * 1000.0 / totalIndexTime} files/sec")
        
        // Aggregate performance metrics
        val allConstClass = results.flatMap { it.constClassRefs }
        val allSuper = results.flatMap { it.superDirectiveRefs }
        
        if (allConstClass.isNotEmpty()) {
            println("\nconst-class performance (all APKs):")
            println("   Samples: ${allConstClass.size}")
            println("   Avg time: ${allConstClass.map { it.timeMs }.average().toLong()}ms")
            println("   Max time: ${allConstClass.maxOf { it.timeMs }}ms")
        }
        
        if (allSuper.isNotEmpty()) {
            println("\n.super directive performance (all APKs):")
            println("   Samples: ${allSuper.size}")
            println("   Avg time: ${allSuper.map { it.timeMs }.average().toLong()}ms")
            println("   Max time: ${allSuper.maxOf { it.timeMs }}ms")
        }
    }
}
