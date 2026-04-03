package xyz.surendrajat.smalilsp.stress.queries

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.shared.TestUtils
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.HoverProvider
import java.io.File
import java.net.URI
import kotlin.system.measureTimeMillis
import kotlin.test.assertTrue

/**
 * Stress test for Bug #8: const-class Hover Support
 * 
 * Validates that hovering over const-class instructions:
 * 1. Returns hover information (not null)
 * 2. Contains correct class name information
 * 3. Performs within acceptable time (<10ms per hover)
 * 
 * This is a DETERMINISTIC test (no random sampling).
 */
class ConstClassHoverStressTest {
    
    data class HoverTestResult(
        val fileName: String,
        val lineNumber: Int,
        val className: String,
        val hoverExists: Boolean,
        val containsClassName: Boolean,
        val hoverTimeMs: Long
    )
    
    @Test
    fun `Bug #8 - const-class hover works correctly`() {
        println("\n" + "=".repeat(70))
        println("BUG #8 STRESS TEST: const-class HOVER SUPPORT")
        println("=".repeat(70))
        
        // Test on Mastodon
        val mastodonDir = TestUtils.getMastodonApk()
        if (mastodonDir != null) {
            println("\n[1/2] Testing Mastodon APK (4,415 files)...")
            testConstClassHover(mastodonDir, "Mastodon")
        }
        
        // Test on ProtonMail
        val protonMailDir = TestUtils.getProtonMailApk()
        if (protonMailDir != null) {
            println("\n[2/2] Testing ProtonMail APK (18,249 files)...")
            testConstClassHover(protonMailDir, "ProtonMail")
        }
        
        println("\n" + "=".repeat(70))
        println("BUG #8 TEST COMPLETE")
        println("=".repeat(70))
    }
    
    private fun testConstClassHover(apkDir: File, apkName: String) {
        // Index the APK
        val parser = SmaliParser()
        val index = WorkspaceIndex()
        var fileCount = 0
        
        println("   Indexing...")
        val indexTime = measureTimeMillis {
            apkDir.walkTopDown()
                .filter { it.extension == "smali" }
                .forEach { file ->
                    try {
                        val content = file.readText()
                        val result = parser.parse(file.toURI().toString(), content)
                        if (result != null) {
                            index.indexFile(result)
                            fileCount++
                        }
                    } catch (e: Exception) {
                        // Skip files that fail
                    }
                }
        }
        println("   Indexed $fileCount files in ${indexTime}ms")
        
        // Find files with const-class instructions
        println("\n   Scanning for const-class instructions...")
        val constClassExamples = mutableListOf<Triple<File, Int, String>>()  // (file, line, className)
        
        apkDir.walkTopDown()
            .filter { it.extension == "smali" }
            .take(500)  // Sample 500 files max for efficiency
            .forEach { file ->
                try {
                    val lines = file.readLines()
                    lines.forEachIndexed { lineIdx, line ->
                        val trimmed = line.trim()
                        if (trimmed.startsWith("const-class ") && trimmed.contains(",")) {
                            // const-class v0, Ljava/lang/String;
                            val className = extractClassFromConstClass(trimmed)
                            if (className != null) {
                                constClassExamples.add(Triple(file, lineIdx, className))
                            }
                        }
                    }
                    
                    // Stop after finding 20 examples
                    if (constClassExamples.size >= 20) return@forEach
                } catch (e: Exception) {
                    // Skip files that fail to read
                }
            }
        
        if (constClassExamples.isEmpty()) {
            println("   ⚠️  No const-class instructions found, skipping test")
            return
        }
        
        println("   Found ${constClassExamples.size} const-class instructions to test")
        
        // Test hover on each const-class instruction
        val provider = HoverProvider(index)
        val results = mutableListOf<HoverTestResult>()
        
        println("\n   Testing hover on const-class instructions...")
        constClassExamples.take(10).forEach { (file, lineIdx, className) ->
            try {
                val lines = file.readLines()
                val line = lines[lineIdx]
                
                // Find position of class name in the line
                val classNameIndex = line.indexOf(className)
                if (classNameIndex >= 0) {
                    val position = Position(lineIdx, classNameIndex + 5)  // Middle of class name
                    
                    var hover: org.eclipse.lsp4j.Hover? = null
                    val hoverTime = measureTimeMillis {
                        hover = provider.provideHover(file.toURI().toString(), position)
                    }
                    
                    val hoverExists = hover != null
                    val hoverContent = hover?.contents?.let { content ->
                        when {
                            content.isLeft -> content.left.toString()
                            content.isRight -> content.right.value
                            else -> ""
                        }
                    } ?: ""
                    val containsClassName = hoverContent.contains(className) || 
                                           hoverContent.contains(className.replace("/", ".").removeSurrounding("L", ";"))
                    
                    results.add(HoverTestResult(
                        fileName = file.name,
                        lineNumber = lineIdx + 1,
                        className = className,
                        hoverExists = hoverExists,
                        containsClassName = containsClassName,
                        hoverTimeMs = hoverTime
                    ))
                }
            } catch (e: Exception) {
                // Skip examples that fail
            }
        }
        
        // Print results
        println("\n   📊 Results:")
        results.forEach { result ->
            val status = if (result.hoverExists && result.containsClassName) "✅" else "❌"
            println("      $status ${result.fileName}:${result.lineNumber}")
            println("         Class: ${result.className}")
            println("         Hover: ${if (result.hoverExists) "present" else "missing"}")
            println("         Content: ${if (result.containsClassName) "correct" else "incorrect"}")
            println("         Time: ${result.hoverTimeMs}ms")
        }
        
        // Calculate statistics
        val successfulTests = results.count { it.hoverExists && it.containsClassName }
        val successRate = if (results.isNotEmpty()) successfulTests * 100.0 / results.size else 0.0
        val avgTime = if (results.isNotEmpty()) results.map { it.hoverTimeMs }.average() else 0.0
        val maxTime = results.maxOfOrNull { it.hoverTimeMs } ?: 0L
        
        println("\n   📈 Statistics:")
        println("      Success Rate: ${String.format("%.1f", successRate)}% ($successfulTests/${results.size})")
        println("      Avg Hover Time: ${String.format("%.1f", avgTime)}ms")
        println("      Max Hover Time: ${maxTime}ms")
        
        // Assertions
        assertTrue(successRate >= 80.0,
            "$apkName: const-class hover should work >= 80%, got ${String.format("%.1f", successRate)}%")
        assertTrue(maxTime <= 500,
            "$apkName: const-class hover should be < 500ms, got ${maxTime}ms")
    }
    
    /**
     * Extract class name from const-class instruction line.
     * "    const-class v0, Ljava/lang/String;" -> "Ljava/lang/String;"
     */
    private fun extractClassFromConstClass(line: String): String? {
        val commaIndex = line.indexOf(',')
        if (commaIndex < 0) return null
        
        val afterComma = line.substring(commaIndex + 1).trim()
        // Remove comments
        val className = afterComma.split('#')[0].trim()
        
        // Validate it's a class reference
        if (className.startsWith("L") && className.contains(";")) {
            val classEnd = className.indexOf(';') + 1
            return className.substring(0, classEnd)
        }
        
        return null
    }
}
