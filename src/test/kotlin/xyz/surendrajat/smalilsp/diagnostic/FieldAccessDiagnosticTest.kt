package xyz.surendrajat.smalilsp

import org.eclipse.lsp4j.Position
import xyz.surendrajat.smalilsp.TestUtils
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.providers.HoverProvider
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Diagnostic test for iget/iput field access instructions.
 */
@Timeout(5, unit = TimeUnit.MINUTES)
class FieldAccessDiagnosticTest {
    
    @Test
    fun `diagnose iget and iput field access`() {
        println("\n=== FIELD ACCESS (iget/iput) DIAGNOSTIC ===\n")
        
        val apkDir = TestUtils.getMastodonApk() ?: return
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
        
        var tested = 0
        var hoverSuccess = 0
        var defSuccess = 0
        val failures = mutableListOf<String>()
        
        println("\nTesting field access instructions...")
        
        fileLoop@ for (file in files.take(500)) {
            if (!file.exists()) continue
            
            val uri = file.toURI().toString()
            val lines = file.readLines()
            
            for ((lineNum, line) in lines.withIndex()) {
                // Find iget/iput/sget/sput instructions
                val trimmed = line.trim()
                if (trimmed.startsWith("iget") || trimmed.startsWith("iput") || 
                    trimmed.startsWith("sget") || trimmed.startsWith("sput")) {
                    
                    // Extract field name position
                    // Format: iget-object v0, v1, Lclass;->field:Type
                    val arrowIndex = line.indexOf("->")
                    if (arrowIndex >= 0) {
                        val colonIndex = line.indexOf(":", arrowIndex)
                        if (colonIndex > arrowIndex + 2) {
                            // Field name is between -> and :
                            val fieldNameStart = arrowIndex + 2
                            val fieldName = line.substring(fieldNameStart, colonIndex)
                            
                            // Extract class name to check SDK vs workspace
                            val classMatch = Regex("""(L[^;]+;)->""").find(line)
                            val className = classMatch?.groups?.get(1)?.value ?: ""
                            val isSDK = className.startsWith("Ljava/") || className.startsWith("Ljavax/") || 
                                        className.startsWith("Landroid/") || className.startsWith("Ldalvik/") ||
                                        className.startsWith("Lkotlin/") || className.startsWith("Lkotlinx/")
                            
                            if (isSDK) continue
                            
                            // Test hover and definition at field name position
                            val cursorPos = fieldNameStart + fieldName.length / 2
                            val position = Position(lineNum, cursorPos)
                            
                            val hover = hoverProvider.provideHover(uri, position)
                            val definition = definitionProvider.findDefinition(uri, position)
                            
                            tested++
                            if (hover != null) hoverSuccess++
                            if (definition.isNotEmpty()) defSuccess++
                            
                            if ((hover == null || definition.isEmpty()) && failures.size < 30) {
                                failures.add("""
                                    File: ${file.path.substringAfterLast("mastodon_decompiled/")}
                                    Line ${lineNum + 1}: ${trimmed}
                                    Class: $className
                                    Field: $fieldName at char $cursorPos
                                    Hover: ${if (hover != null) "✓" else "✗"}
                                    Definition: ${if (definition.isNotEmpty()) "✓" else "✗"}
                                """.trimIndent())
                            }
                            
                            if (tested >= 1000) break@fileLoop
                        }
                    }
                }
            }
        }
        
        println("\n=== RESULTS ===")
        println("Tested: $tested field access instructions")
        println("Hover Success: $hoverSuccess (${hoverSuccess * 100 / tested.coerceAtLeast(1)}%)")
        println("Definition Success: $defSuccess (${defSuccess * 100 / tested.coerceAtLeast(1)}%)")
        
        println("\n=== FAILURES (up to 30) ===")
        failures.forEach { println(it); println() }
    }
}
