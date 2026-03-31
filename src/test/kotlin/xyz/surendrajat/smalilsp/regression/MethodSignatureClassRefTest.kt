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
 * Diagnostic test for class references in method/field signatures.
 */
@Timeout(5, unit = TimeUnit.MINUTES)
class MethodSignatureClassRefTest {
    
    @Test
    fun `diagnose class refs in method and field signatures`() {
        println("\n=== METHOD/FIELD SIGNATURE CLASS REF DIAGNOSTIC ===\n")
        
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
        
        // Test class refs in method signatures
        var methodTests = 0
        var methodHoverSuccess = 0
        var methodDefSuccess = 0
        val methodFailures = mutableListOf<String>()
        
        // Test class refs in field signatures  
        var fieldTests = 0
        var fieldHoverSuccess = 0
        var fieldDefSuccess = 0
        val fieldFailures = mutableListOf<String>()
        
        println("\nTesting class refs in signatures...")
        
        fileLoop@ for (file in files.take(500)) {
            if (!file.exists()) continue
            
            val uri = file.toURI().toString()
            val lines = file.readLines()
            
            for ((lineNum, line) in lines.withIndex()) {
                // Test .method signatures
                if (line.trim().startsWith(".method") && methodTests < 500) {
                    val classMatches = Regex("""(L[a-zA-Z0-9/\$]+;)""").findAll(line).toList()
                    
                    for (match in classMatches) {
                        val className = match.value
                        val isSDK = className.startsWith("Ljava/") || className.startsWith("Ljavax/") || 
                                    className.startsWith("Landroid/") || className.startsWith("Ldalvik/") ||
                                    className.startsWith("Lkotlin/") || className.startsWith("Lkotlinx/")
                        
                        if (isSDK) continue
                        
                        val cursorPos = match.range.first + className.length / 2
                        val position = Position(lineNum, cursorPos)
                        
                        val hover = hoverProvider.provideHover(uri, position)
                        val definition = definitionProvider.findDefinition(uri, position)
                        
                        methodTests++
                        if (hover != null) methodHoverSuccess++
                        if (definition.isNotEmpty()) methodDefSuccess++
                        
                        if ((hover == null || definition.isEmpty()) && methodFailures.size < 20) {
                            methodFailures.add("""
                                File: ${file.path.substringAfterLast("mastodon_decompiled/")}
                                Line ${lineNum + 1}: ${line.trim()}
                                Class: $className at char $cursorPos
                                Hover: ${if (hover != null) "✓" else "✗"}
                                Definition: ${if (definition.isNotEmpty()) "✓" else "✗"}
                            """.trimIndent())
                        }
                        
                        if (methodTests >= 500 && fieldTests >= 500) break@fileLoop
                    }
                }
                
                // Test .field signatures
                if (line.trim().startsWith(".field") && fieldTests < 500) {
                    val colonIndex = line.indexOf(':')
                    if (colonIndex >= 0) {
                        val classMatch = Regex("""(L[a-zA-Z0-9/\$]+;)""").find(line, colonIndex)
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
                            
                            fieldTests++
                            if (hover != null) fieldHoverSuccess++
                            if (definition.isNotEmpty()) fieldDefSuccess++
                            
                            if ((hover == null || definition.isEmpty()) && fieldFailures.size < 20) {
                                fieldFailures.add("""
                                    File: ${file.path.substringAfterLast("mastodon_decompiled/")}
                                    Line ${lineNum + 1}: ${line.trim()}
                                    Class: $className at char $cursorPos
                                    Hover: ${if (hover != null) "✓" else "✗"}
                                    Definition: ${if (definition.isNotEmpty()) "✓" else "✗"}
                                """.trimIndent())
                            }
                            
                            if (methodTests >= 500 && fieldTests >= 500) break@fileLoop
                        }
                    }
                }
            }
        }
        
        println("\n=== METHOD SIGNATURE RESULTS ===")
        println("Tested: $methodTests class refs in .method signatures")
        println("Hover Success: $methodHoverSuccess (${methodHoverSuccess * 100 / methodTests.coerceAtLeast(1)}%)")
        println("Definition Success: $methodDefSuccess (${methodDefSuccess * 100 / methodTests.coerceAtLeast(1)}%)")
        
        println("\n=== FIELD SIGNATURE RESULTS ===")
        println("Tested: $fieldTests class refs in .field signatures")
        println("Hover Success: $fieldHoverSuccess (${fieldHoverSuccess * 100 / fieldTests.coerceAtLeast(1)}%)")
        println("Definition Success: $fieldDefSuccess (${fieldDefSuccess * 100 / fieldTests.coerceAtLeast(1)}%)")
        
        println("\n=== METHOD SIGNATURE FAILURES (up to 20) ===")
        methodFailures.forEach { println(it); println() }
        
        println("\n=== FIELD SIGNATURE FAILURES (up to 20) ===")
        fieldFailures.forEach { println(it); println() }
    }
}
