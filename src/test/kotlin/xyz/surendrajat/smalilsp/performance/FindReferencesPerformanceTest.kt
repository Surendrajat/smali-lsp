package xyz.surendrajat.smalilsp.performance

import org.eclipse.lsp4j.Position
import xyz.surendrajat.smalilsp.TestUtils
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/**
 * Performance test for find references to identify slowdowns.
 */
@Timeout(15, unit = TimeUnit.MINUTES)
class FindReferencesPerformanceTest {
    
    @Test
    fun `test find references performance on Mastodon`() {
        println("\n=== FIND REFERENCES PERFORMANCE TEST (MASTODON) ===\n")
        
        val apkDir = TestUtils.getMastodonApk() ?: return
        require(apkDir.exists()) { "Mastodon APK not found at ${apkDir.absolutePath}" }
        
        // Index workspace
        println("Indexing Mastodon...")
        val startIndex = System.currentTimeMillis()
        val parser = SmaliParser()
        val workspaceIndex = WorkspaceIndex()
        
        val files = apkDir.walkTopDown()
            .filter { it.extension == "smali" }
            .toList()
        
        files.forEach { file ->
            try {
                val content = file.readText()
                val uri = file.toURI().toString()
                val smaliFile = parser.parse(uri, content)
                if (smaliFile != null) {
                    workspaceIndex.indexFile(smaliFile)
                }
            } catch (e: Exception) {
                // Continue
            }
        }
        
        val indexTime = System.currentTimeMillis() - startIndex
        println("Indexed ${files.size} files in ${indexTime}ms\n")
        
        val provider = ReferenceProvider(workspaceIndex)
        
        // Test 1: Find references for a common class (should be many refs)
        println("Test 1: Find references for common workspace class...")
        val classPattern = Regex("""\.class.* (L[a-zA-Z0-9/\$]+;)""")
        
        var commonClass: String? = null
        var commonClassUri: String? = null
        var commonClassLine = 0
        
        // Find a common workspace class (not SDK)
        for (file in files.take(100)) {
            val content = file.readText()
            val uri = file.toURI().toString()
            val match = classPattern.find(content)
            if (match != null) {
                val className = match.groups[1]!!.value
                if (!isSDK(className)) {
                    commonClass = className
                    commonClassUri = uri
                    commonClassLine = content.substring(0, match.range.first).count { it == '\n' }
                    break
                }
            }
        }
        
        if (commonClass != null && commonClassUri != null) {
            println("Testing class: $commonClass")
            
            val start = System.currentTimeMillis()
            val refs = provider.findReferences(commonClassUri, Position(commonClassLine, 10), true)
            val time = System.currentTimeMillis() - start
            
            println("  References found: ${refs.size}")
            println("  Time: ${time}ms")
            
            assertTrue(time < 1200, "Find references took ${time}ms (should be <1200ms)")
        }
        
        // Test 2: Find references for methods
        println("\nTest 2: Find references for common method...")
        val methodPattern = Regex("""\.method.* (\w+)\(""")
        
        var methodName: String? = null
        var methodUri: String? = null
        var methodLine = 0
        
        for (file in files.take(100)) {
            val content = file.readText()
            val uri = file.toURI().toString()
            val match = methodPattern.find(content)
            if (match != null) {
                methodName = match.groups[1]!!.value
                methodUri = uri
                methodLine = content.substring(0, match.range.first).count { it == '\n' }
                break
            }
        }
        
        if (methodName != null && methodUri != null) {
            println("Testing method: $methodName")
            
            val start = System.currentTimeMillis()
            val refs = provider.findReferences(methodUri, Position(methodLine, 10), true)
            val time = System.currentTimeMillis() - start
            
            println("  References found: ${refs.size}")
            println("  Time: ${time}ms")
            
            assertTrue(time < 1200, "Find references took ${time}ms (should be <1200ms)")
        }
        
        // Test 3: Sample 50 random positions
        println("\nTest 3: Sample 50 random reference lookups...")
        val times = mutableListOf<Long>()
        
        for (i in 0 until 50) {
            val file = files.random()
            val lines = file.readLines()
            val randomLine = lines.indices.random()
            val uri = file.toURI().toString()
            
            val start = System.currentTimeMillis()
            provider.findReferences(uri, Position(randomLine, 5), true)
            val time = System.currentTimeMillis() - start
            times.add(time)
        }
        
        val avgTime = times.average()
        val maxTime = times.maxOrNull() ?: 0
        val slowSamples = times.count { it > 100 }
        
        println("  Average: ${avgTime.toLong()}ms")
        println("  Max: ${maxTime}ms")
        println("  Samples >100ms: $slowSamples")
        
        // Note: Threshold based on real-world variance across multiple runs
        // Observed: 11-22 slow samples depending on system load, avg 88-106ms
        assertTrue(avgTime < 110, "Average find references time ${avgTime.toLong()}ms (should be <110ms)")
        assertTrue(slowSamples <= 25, "Too many slow samples: $slowSamples (should be <=25, found $slowSamples)")
    }
    
    @Test
    fun `test find references performance on ProtonMail`() {
        println("\n=== FIND REFERENCES PERFORMANCE TEST (PROTONMAIL) ===\n")
        
        val apkDir = TestUtils.getProtonMailApk() ?: return
        require(apkDir.exists()) { "ProtonMail APK not found at ${apkDir.absolutePath}" }
        
        // Index workspace
        println("Indexing ProtonMail (this may take a while)...")
        val startIndex = System.currentTimeMillis()
        val parser = SmaliParser()
        val workspaceIndex = WorkspaceIndex()
        
        val files = apkDir.walkTopDown()
            .filter { it.extension == "smali" }
            .toList()
        
        println("Found ${files.size} files...")
        
        files.forEach { file ->
            try {
                val content = file.readText()
                val uri = file.toURI().toString()
                val smaliFile = parser.parse(uri, content)
                if (smaliFile != null) {
                    workspaceIndex.indexFile(smaliFile)
                }
            } catch (e: Exception) {
                // Continue
            }
        }
        
        val indexTime = System.currentTimeMillis() - startIndex
        println("Indexed ${files.size} files in ${indexTime}ms (${indexTime/1000}s)\n")
        
        val provider = ReferenceProvider(workspaceIndex)
        
        // Test: Sample 100 random positions
        println("Testing 100 random reference lookups...")
        val times = mutableListOf<Long>()
        
        for (i in 0 until 100) {
            val file = files.random()
            val lines = file.readLines()
            val randomLine = lines.indices.random()
            val uri = file.toURI().toString()
            
            val start = System.currentTimeMillis()
            provider.findReferences(uri, Position(randomLine, 5), true)
            val time = System.currentTimeMillis() - start
            times.add(time)
            
            if ((i + 1) % 25 == 0) {
                println("  Completed ${i + 1}/100...")
            }
        }
        
        val avgTime = times.average()
        val maxTime = times.maxOrNull() ?: 0
        val slowSamples = times.count { it > 100 }
        val verySlow = times.count { it > 1200 }
        
        println("\n=== RESULTS ===")
        println("  Average: ${avgTime.toLong()}ms")
        println("  Max: ${maxTime}ms")
        println("  Samples >100ms: $slowSamples")
        println("  Samples >1200ms: $verySlow")
        
        println("\n=== PERCENTILES ===")
        val sorted = times.sorted()
        println("  p50: ${sorted[49]}ms")
        println("  p90: ${sorted[89]}ms")
        println("  p95: ${sorted[94]}ms")
        println("  p99: ${sorted[98]}ms")
        
        assertTrue(avgTime < 500, "Average find references time ${avgTime.toLong()}ms (should be <500ms)")
        assertTrue(verySlow == 0, "Found $verySlow samples >1200ms (should be 0)")
    }
    
    private fun isSDK(className: String): Boolean {
        return className.startsWith("Ljava/") || className.startsWith("Ljavax/") || 
               className.startsWith("Landroid/") || className.startsWith("Ldalvik/") ||
               className.startsWith("Lkotlin/") || className.startsWith("Lkotlinx/")
    }
}
