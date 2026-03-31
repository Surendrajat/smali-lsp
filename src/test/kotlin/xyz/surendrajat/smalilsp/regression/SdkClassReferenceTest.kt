package xyz.surendrajat.smalilsp.regression

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.TestUtils
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import java.io.File
import kotlin.test.assertTrue

/**
 * Tests for SDK class reference behavior on real APK decompiled files.
 * Verifies that finding references to SDK base classes returns reasonable result counts.
 */
class SdkClassReferenceTest {
    
    @Test
    fun `find refs on Object init in Mastodon should return reasonable count`() {
        testSdkClassReferences(
            apkName = "Mastodon",
            apkDir = TestUtils.getMastodonApk()!!,
            sdkClassName = "Ljava/lang/Object;",
            methodName = "<init>",
            descriptor = "()V",
            maxExpected = 1000 // Object.<init> is called by every constructor, expect many results
        )
    }
    
    @Test
    fun `find refs on Enum init in Mastodon should return reasonable count`() {
        testSdkClassReferences(
            apkName = "Mastodon",
            apkDir = TestUtils.getMastodonApk()!!,
            sdkClassName = "Ljava/lang/Enum;",
            methodName = "<init>",
            descriptor = "(Ljava/lang/String;I)V",
            maxExpected = 200 // Enum.<init> called by each enum constructor, expect moderate results
        )
    }
    
    @Test
    fun `find refs on Object init in ProtonMail should return reasonable count`() {
        testSdkClassReferences(
            apkName = "ProtonMail",
            apkDir = TestUtils.getProtonMailApk()!!,
            sdkClassName = "Ljava/lang/Object;",
            methodName = "<init>",
            descriptor = "()V",
            maxExpected = 1000 // Object.<init> is called by every constructor, expect many results
        )
    }
    
    @Test
    fun `find refs on Enum init in ProtonMail should return reasonable count`() {
        testSdkClassReferences(
            apkName = "ProtonMail",
            apkDir = TestUtils.getProtonMailApk()!!,
            sdkClassName = "Ljava/lang/Enum;",
            methodName = "<init>",
            descriptor = "(Ljava/lang/String;I)V",
            maxExpected = 600 // ProtonMail has many enums, expect moderate-high results
        )
    }
    
    @Test
    fun `find refs on Object init in Mastodon2 should return reasonable count`() {
        testSdkClassReferences(
            apkName = "Mastodon",
            apkDir = TestUtils.getMastodonApk()!!,
            sdkClassName = "Ljava/lang/Object;",
            methodName = "<init>",
            descriptor = "()V",
            maxExpected = 1000 // Object.<init> is called by every constructor, expect many results
        )
    }
    
    @Test
    fun `find refs on Enum init in Mastodon2 should return reasonable count`() {
        testSdkClassReferences(
            apkName = "Mastodon",
            apkDir = TestUtils.getMastodonApk()!!,
            sdkClassName = "Ljava/lang/Enum;",
            methodName = "<init>",
            descriptor = "(Ljava/lang/String;I)V",
            maxExpected = 200 // Enum.<init> called by each enum constructor, expect moderate results
        )
    }
    
    private fun testSdkClassReferences(
        apkName: String,
        apkDir: File,
        sdkClassName: String,
        methodName: String,
        descriptor: String,
        maxExpected: Int
    ) {
        if (!apkDir.exists()) {
            println("SKIPPED: $apkName not found at ${apkDir.absolutePath}")
            return
        }
        
        println("\n===== Testing $apkName: $sdkClassName->$methodName$descriptor =====")
        
        // Index all smali files
        val parser = SmaliParser()
        val index = WorkspaceIndex()
        var fileCount = 0
        
        val startIndex = System.currentTimeMillis()
        apkDir.walk()
            .filter { it.isFile && it.extension == "smali" }
            .take(1000) // Limit to first 1000 files for reasonable test time
            .forEach { file ->
                val uri = file.toURI().toString()
                val content = file.readText()
                val parsed = parser.parse(uri, content)
                if (parsed != null) {
                    index.indexFile(parsed)
                    fileCount++
                }
            }
        val indexTime = System.currentTimeMillis() - startIndex
        
        println("Indexed $fileCount files in ${indexTime}ms")
        
        // Find a file that calls this SDK method
        val sampleFile = apkDir.walk()
            .filter { it.isFile && it.extension == "smali" }
            .take(1000)
            .firstOrNull { file ->
                file.readText().contains("$sdkClassName->$methodName$descriptor")
            }
        
        if (sampleFile == null) {
            println("SKIPPED: No file found calling $sdkClassName->$methodName$descriptor")
            return
        }
        
        println("Sample file: ${sampleFile.name}")
        
        // Find the line with the method call
        val lines = sampleFile.readLines()
        val lineIndex = lines.indexOfFirst { it.contains("$sdkClassName->$methodName$descriptor") }
        if (lineIndex == -1) {
            println("SKIPPED: Method call not found in ${sampleFile.name}")
            return
        }
        
        val line = lines[lineIndex]
        val methodNamePos = line.indexOf(methodName) + 2 // Click in middle of method name
        val sampleUri = sampleFile.toURI().toString()
        
        val provider = ReferenceProvider(index)
        
        // Find references
        val startFind = System.currentTimeMillis()
        val refs = provider.findReferences(sampleUri, Position(lineIndex, methodNamePos), false)
        val findTime = System.currentTimeMillis() - startFind
        
        println("Found ${refs.size} references in ${findTime}ms")
        
        // Print first 10 refs
        refs.take(10).forEach { loc ->
            val fileName = loc.uri.substringAfterLast("/")
            val line = loc.range.start.line
            println("  $fileName:$line")
        }
        if (refs.size > 10) {
            println("  ... and ${refs.size - 10} more")
        }
        
        // Assert reasonable count
        assertTrue(
            refs.size <= maxExpected,
            "Expected <= $maxExpected refs for SDK class $sdkClassName, but got ${refs.size}. " +
            "This suggests SDK direct-call-only logic is not working."
        )
        
        // Assert reasonable performance
        assertTrue(
            findTime < 5000,
            "Expected find refs to complete in <5000ms, but took ${findTime}ms"
        )
        
        println("✅ PASSED: ${refs.size} refs (<= $maxExpected), ${findTime}ms (< 5000ms)")
    }
}
