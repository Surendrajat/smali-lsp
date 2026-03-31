package xyz.surendrajat.smalilsp.performance

import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import org.eclipse.lsp4j.Position
import kotlin.system.measureTimeMillis
import java.nio.file.Paths
import java.io.File

/**
 * Real-world performance test for SDK class reference finding.
 * 
 * Tests performance with Mastodon APK (4,415 files).
 * Measures:
 * 1. Index building time
 * 2. SDK method reference finding time
 * 3. SDK field reference finding time
 * 4. Workspace method reference finding time (comparison)
 * 
 * Performance targets:
 * - Index build: < 10 seconds
 * - SDK reference find: < 500ms
 * - Workspace reference find: < 500ms
 */
class SDKReferencePerformanceTest {
    
    @Test
    fun `SDK reference performance with real APK`() {
        println("\n=== SDK Reference Performance Test ===")
        println("Using Mastodon APK (4,415 files)")
        
        // 1. Build index
        val apkPath = Paths.get("apk/mastodon_decompiled").toAbsolutePath().toString()
        val apkDir = File(apkPath)
        
        if (!apkDir.exists()) {
            println("⚠️  Mastodon APK not found at: $apkPath")
            println("Skipping performance test")
            return
        }
        
        val parser = SmaliParser()
        val index = WorkspaceIndex()
        
        var fileCount = 0
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
                        // Skip files that fail to parse
                    }
                }
        }
        
        println("\n📊 Index Building:")
        println("   Files indexed: $fileCount")
        println("   Time: ${indexTime}ms (${indexTime / 1000.0}s)")
        println("   Avg per file: ${indexTime / fileCount}ms")
        
        // 2. Find a file with SDK method calls
        val provider = ReferenceProvider(index)
        var testFile: File? = null
        var sdkMethodLine = -1
        
        // Search for String.valueOf calls (common SDK method)
        apkDir.walkTopDown()
            .filter { it.extension == "smali" }
            .take(100)  // Only check first 100 files
            .forEach { file ->
                if (testFile == null) {
                    val lines = file.readLines()
                    lines.forEachIndexed { index, line ->
                        if (line.contains("Ljava/lang/String;->valueOf")) {
                            testFile = file
                            sdkMethodLine = index
                            return@forEach
                        }
                    }
                }
            }
        
        if (testFile != null && sdkMethodLine >= 0) {
            val uri = testFile!!.toURI().toString()
            val line = testFile!!.readLines()[sdkMethodLine]
            val methodPos = line.indexOf("->valueOf")
            
            if (methodPos >= 0) {
                println("\n📊 SDK Method Reference Finding:")
                println("   Method: String.valueOf")
                println("   File: ${testFile!!.name}")
                
                var refCount = 0
                val sdkRefTime = measureTimeMillis {
                    val refs = provider.findReferences(
                        uri,
                        Position(sdkMethodLine, methodPos + 3),
                        false
                    )
                    refCount = refs.size
                }
                
                println("   References found: $refCount")
                println("   Time: ${sdkRefTime}ms")
            }
        } else {
            println("\n⚠️  No SDK method calls found for testing")
        }
        
        // 3. Find a workspace method for comparison
        val allFiles = index.getAllFiles()
        if (allFiles.isNotEmpty()) {
            val sampleFile = allFiles.first()
            if (sampleFile.methods.isNotEmpty()) {
                val sampleMethod = sampleFile.methods.first()
                
                println("\n📊 Workspace Method Reference Finding (comparison):")
                println("   Method: ${sampleFile.classDefinition.name}.${sampleMethod.name}")
                
                var workspaceRefCount = 0
                val workspaceRefTime = measureTimeMillis {
                    val refs = provider.findReferences(
                        sampleFile.uri,
                        Position(sampleMethod.range.start.line, sampleMethod.range.start.character + 5),
                        true
                    )
                    workspaceRefCount = refs.size
                }
                
                println("   References found: $workspaceRefCount")
                println("   Time: ${workspaceRefTime}ms")
            }
        }
        
        println("\n✅ Performance test complete")
        println("Note: This is an observational test - no strict assertions")
        println("Review times above to ensure they are reasonable (< 500ms for refs)")
    }
}
