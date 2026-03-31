package xyz.surendrajat.smalilsp

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.providers.HoverProvider
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Diagnostic test for const-class and check-cast instructions.
 */
@Timeout(5, unit = TimeUnit.MINUTES)
class ConstClassCheckCastTest {
    
    @Test
    fun `diagnose const-class and check-cast`() {
        println("\n=== CONST-CLASS AND CHECK-CAST DIAGNOSTIC ===\n")
        
        val apkDir = File("apk/mastodon_decompiled")
        require(apkDir.exists()) { "APK directory not found" }
        
        // Index workspace
        println("Indexing workspace...")
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
                // Continue on error
            }
        }
        
        println("Indexed ${files.size} files")
        
        val definitionProvider = DefinitionProvider(workspaceIndex)
        val hoverProvider = HoverProvider(workspaceIndex)
        
        var constClassTests = 0
        var constClassHover = 0
        var constClassDef = 0
        val constClassFailures = mutableListOf<String>()
        
        var checkCastTests = 0
        var checkCastHover = 0
        var checkCastDef = 0
        val checkCastFailures = mutableListOf<String>()
        
        println("\nTesting const-class and check-cast instructions...")
        
        fileLoop@ for (file in files.take(500)) {
            if (!file.exists()) continue
            
            val uri = file.toURI().toString()
            val lines = file.readLines()
            
            for ((lineNum, line) in lines.withIndex()) {
                val trimmed = line.trim()
                
                // Test const-class
                if (trimmed.startsWith("const-class")) {
                    // Format: const-class v0, Lclass;
                    val classMatch = Regex("""(L[a-zA-Z0-9/\$]+;)""").find(line)
                    if (classMatch != null) {
                        val className = classMatch.value
                        val isSDK = className.startsWith("Ljava/") || className.startsWith("Ljavax/") || 
                                    className.startsWith("Landroid/") || className.startsWith("Ldalvik/") ||
                                    className.startsWith("Lkotlin/") || className.startsWith("Lkotlinx/")
                        
                        if (isSDK) continue
                        
                        val cursorPos = classMatch.range.first + className.length / 2
                        val position = Position(lineNum, cursorPos)
                        
                        val hover = hoverProvider.provideHover(uri, position)
                        val definition = definitionProvider.findDefinition(uri, position)
                        
                        constClassTests++
                        if (hover != null) constClassHover++
                        if (definition.isNotEmpty()) constClassDef++
                        
                        if ((hover == null || definition.isEmpty()) && constClassFailures.size < 20) {
                            constClassFailures.add("""
                                File: ${file.path.substringAfterLast("mastodon_decompiled/")}
                                Line ${lineNum + 1}: ${trimmed}
                                Class: $className at char $cursorPos
                                Hover: ${if (hover != null) "✓" else "✗"}
                                Definition: ${if (definition.isNotEmpty()) "✓" else "✗"}
                            """.trimIndent())
                        }
                        
                        if (constClassTests >= 500 && checkCastTests >= 500) break@fileLoop
                    }
                }
                
                // Test check-cast
                if (trimmed.startsWith("check-cast")) {
                    // Format: check-cast v0, Lclass;
                    val classMatch = Regex("""(L[a-zA-Z0-9/\$]+;)""").find(line)
                    if (classMatch != null) {
                        val className = classMatch.value
                        val isSDK = className.startsWith("Ljava/") || className.startsWith("Ljavax/") || 
                                    className.startsWith("Landroid/") || className.startsWith("Ldalvik/") ||
                                    className.startsWith("Lkotlin/") || className.startsWith("Lkotlinx/")
                        
                        if (isSDK) continue
                        
                        val cursorPos = classMatch.range.first + className.length / 2
                        val position = Position(lineNum, cursorPos)
                        
                        val hover = hoverProvider.provideHover(uri, position)
                        val definition = definitionProvider.findDefinition(uri, position)
                        
                        checkCastTests++
                        if (hover != null) checkCastHover++
                        if (definition.isNotEmpty()) checkCastDef++
                        
                        if ((hover == null || definition.isEmpty()) && checkCastFailures.size < 20) {
                            checkCastFailures.add("""
                                File: ${file.path.substringAfterLast("mastodon_decompiled/")}
                                Line ${lineNum + 1}: ${trimmed}
                                Class: $className at char $cursorPos
                                Hover: ${if (hover != null) "✓" else "✗"}
                                Definition: ${if (definition.isNotEmpty()) "✓" else "✗"}
                            """.trimIndent())
                        }
                        
                        if (constClassTests >= 500 && checkCastTests >= 500) break@fileLoop
                    }
                }
            }
        }
        
        println("\n=== CONST-CLASS RESULTS ===")
        println("Tested: $constClassTests")
        println("Hover Success: $constClassHover (${constClassHover * 100 / constClassTests.coerceAtLeast(1)}%)")
        println("Definition Success: $constClassDef (${constClassDef * 100 / constClassTests.coerceAtLeast(1)}%)")
        
        println("\n=== CHECK-CAST RESULTS ===")
        println("Tested: $checkCastTests")
        println("Hover Success: $checkCastHover (${checkCastHover * 100 / checkCastTests.coerceAtLeast(1)}%)")
        println("Definition Success: $checkCastDef (${checkCastDef * 100 / checkCastTests.coerceAtLeast(1)}%)")
        
        println("\n=== CONST-CLASS FAILURES (up to 20) ===")
        constClassFailures.forEach { println(it); println() }
        
        println("\n=== CHECK-CAST FAILURES (up to 20) ===")
        checkCastFailures.forEach { println(it); println() }
    }
}
