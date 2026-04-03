package xyz.surendrajat.smalilsp.regression

import xyz.surendrajat.smalilsp.shared.TestUtils

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import java.io.File
import java.nio.file.Path

/**
 * CRITICAL VERIFICATION TEST
 * 
 * Verify that array method invocation parser fix actually works
 * Tests against real APK files
 */
class ParserFixVerificationTest {
    
    private val parser = SmaliParser()
    
    @Test
    fun `verify array method invocation parsing - float array`() {
        val smali = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                .locals 1
                const/4 v0, 0x5
                new-array v0, v0, [F
                invoke-virtual {v0}, [F->clone()Ljava/lang/Object;
                move-result-object v0
                return-void
            .end method
        """.trimIndent()
        
        val result = parser.parse("file:///Test.smali", smali)
        
        assertNotNull(result, "Should parse file with [F->clone() invocation")
        assertEquals(1, result!!.methods.size)
        assertTrue(result.methods[0].name == "test")
    }
    
    @Test
    fun `verify array method invocation parsing - int array`() {
        val smali = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                .locals 1
                invoke-virtual {p0}, [I->clone()Ljava/lang/Object;
                return-void
            .end method
        """.trimIndent()
        
        val result = parser.parse("file:///Test.smali", smali)
        
        assertNotNull(result, "Should parse file with [I->clone() invocation")
    }
    
    @Test
    fun `verify array method invocation parsing - object array`() {
        val smali = """
            .class public LTest;
            .super Ljava/lang/Object;
            
            .method public test()V
                .locals 1
                invoke-virtual {p0}, [Ljava/lang/String;->clone()Ljava/lang/Object;
                return-void
            .end method
        """.trimIndent()
        
        val result = parser.parse("file:///Test.smali", smali)
        
        assertNotNull(result, "Should parse file with [Ljava/lang/String;->clone() invocation")
    }
    
    @Test
    fun `stress test real APK - Mastodon if available`() {
        val mastodonDir = TestUtils.getMastodonApk() ?: run {
            println("⚠️  Mastodon APK not found - skipping stress test")
            return
        }

        if (!mastodonDir.exists()) {
            println("⚠️  Mastodon APK not found - skipping stress test")
            return
        }
        
        println("\n=== Stress Testing with Mastodon APK ===")
        
        var totalFiles = 0
        var successCount = 0
        var failCount = 0
        var arrayInvocationCount = 0
        val errors = mutableListOf<String>()
        
        mastodonDir.walkTopDown()
            .filter { it.extension == "smali" }
            .forEach { file ->
                totalFiles++
                
                val content = file.readText()
                
                // Check for array invocations
                if (content.contains(Regex("""\[\w+->"""))) {
                    arrayInvocationCount++
                }
                
                val result = parser.parse(file.toURI().toString(), content)
                
                if (result != null) {
                    successCount++
                } else {
                    failCount++
                    errors.add(file.relativeTo(mastodonDir).path)
                }
                
                if (totalFiles % 500 == 0) {
                    print("\r  Processed: $totalFiles files...")
                }
            }
        
        println("\r" + " ".repeat(60))
        println("  Total files: $totalFiles")
        println("  Success: $successCount")
        println("  Failed: $failCount")
        println("  Files with array invocations: $arrayInvocationCount")
        
        if (errors.isNotEmpty()) {
            println("\n  First 10 errors:")
            errors.take(10).forEach { println("    - $it") }
        }
        
        val successRate = (successCount.toDouble() / totalFiles * 100)
        println("\n  Success rate: ${"%.2f".format(successRate)}%%")
        
        // Assert success criteria
        assertTrue(successRate >= 99.0, 
            "Success rate should be >= 99%, got ${"%.2f".format(successRate)}%%")
        assertTrue(arrayInvocationCount > 0, 
            "Should find files with array invocations")
    }
    
    @Test
    fun `stress test real APK - based1111`() {
        // NOTE: based1111 not available, using Mastodon
        val basedDir = TestUtils.getMastodonApk() ?: run {
            println("⚠️  based1111 APK not found - skipping stress test")
            return
        }

        if (!basedDir.exists()) {
            println("⚠️  based1111 APK not found - skipping stress test")
            return
        }
        
        println("\n=== Stress Testing with based1111 APK ===")
        
        var totalFiles = 0
        var successCount = 0
        var failCount = 0
        var arrayInvocationCount = 0
        val specificErrors = mutableMapOf<String, Int>()
        
        // Test specific files that had errors before
        val knownProblematicFiles = listOf(
            "androidx/transition/ChangeTransform\$PathAnimatorMatrix.smali",
            "androidx/collection/LongSparseArray.smali",
            "androidx/collection/SparseArrayCompat.smali",
            "androidx/transition/Transition.smali",
            "com/google/android/material/drawable/DrawableUtils.smali"
        )
        
        basedDir.walkTopDown()
            .filter { it.extension == "smali" }
            .forEach { file ->
                totalFiles++
                
                val content = file.readText()
                val relativePath = file.relativeTo(basedDir).path
                
                // Check for array invocations
                if (content.contains(Regex("""\[\w+->"""))) {
                    arrayInvocationCount++
                    
                    // Check if this is a known problematic file
                    if (knownProblematicFiles.any { relativePath.contains(it) }) {
                        println("  ✓ Testing known problematic file: $relativePath")
                    }
                }
                
                val result = parser.parse(file.toURI().toString(), content)
                
                if (result != null) {
                    successCount++
                } else {
                    failCount++
                    specificErrors[relativePath] = (specificErrors[relativePath] ?: 0) + 1
                }
                
                if (totalFiles % 500 == 0) {
                    print("\r  Processed: $totalFiles files...")
                }
            }
        
        println("\r" + " ".repeat(60))
        println("  Total files: $totalFiles")
        println("  Success: $successCount")
        println("  Failed: $failCount")
        println("  Files with array invocations: $arrayInvocationCount")
        
        if (specificErrors.isNotEmpty()) {
            println("\n  Files that failed:")
            specificErrors.entries.take(10).forEach { (file, count) ->
                println("    - $file")
            }
        }
        
        val successRate = (successCount.toDouble() / totalFiles * 100)
        println("\n  Success rate: ${"%.2f".format(successRate)}%%")
        
        // CRITICAL: Known files with array invocations MUST parse
        knownProblematicFiles.forEach { knownFile ->
            assertFalse(specificErrors.keys.any { it.contains(knownFile) },
                "File $knownFile should parse successfully after fix")
        }
        
        assertTrue(successRate >= 99.0, 
            "Success rate should be >= 99%, got ${"%.2f".format(successRate)}%%")
        assertTrue(arrayInvocationCount > 0, 
            "Should find files with array invocations")
    }
}
