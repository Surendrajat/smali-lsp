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
 * Diagnostic test to understand why invoke instruction definitions fail.
 * 
 * Tests specific invoke patterns from Phase 3 failures:
 * - invoke-virtual
 * - invoke-direct
 * - invoke-static
 * - invoke-interface
 */
@Timeout(10, unit = TimeUnit.MINUTES)
class InvokeInstructionDiagnosticTest {
    
    @Test
    fun `diagnose invoke instruction definition failures`() {
        println("\n=== INVOKE INSTRUCTION DIAGNOSTIC ===\n")
        
        // Find test files with invoke instructions
        val apkDir = TestUtils.getMastodonApk() ?: return
        require(apkDir.exists()) { "APK directory not found: ${apkDir.absolutePath}" }
        
        // Index workspace
        println("Indexing workspace...")
        val parser = SmaliParser()
        val workspaceIndex = WorkspaceIndex()
        
        val files = apkDir.walkTopDown()
            .filter { it.extension == "smali" }
            .toList()
        
        val parsedFiles = mutableListOf<ParsedFileInfo>()
        val startIndex = System.currentTimeMillis()
        
        files.forEach { file ->
            try {
                val content = file.readText()
                val uri = file.toURI().toString()
                val smaliFile = parser.parse(uri, content)
                if (smaliFile != null) {
                    workspaceIndex.indexFile(smaliFile)
                    parsedFiles.add(ParsedFileInfo(uri, file.absolutePath, smaliFile.classDefinition.name))
                }
            } catch (e: Exception) {
                // Continue on error
            }
        }
        
        val indexTime = System.currentTimeMillis() - startIndex
        println("Indexed ${parsedFiles.size} files in ${indexTime}ms")
        
        val definitionProvider = DefinitionProvider(workspaceIndex)
        val hoverProvider = HoverProvider(workspaceIndex)
        
        // Test invoke instructions
        var tested = 0
        var invokeSuccesses = 0
        var invokeFailures = 0
        val failureExamples = mutableListOf<String>()
        
        println("\nTesting invoke instructions...")
        
        for (fileInfo in parsedFiles.take(200)) {
            val javaFile = File(fileInfo.path)
            if (!javaFile.exists()) continue
            
            val lines = javaFile.readLines()
            
            lines.forEachIndexed { lineNum, line ->
                // Find invoke instructions
                if (line.trim().startsWith("invoke-")) {
                    // Extract method name position
                    val arrowIndex = line.indexOf("->")
                    if (arrowIndex >= 0) {
                        val openParen = line.indexOf("(", arrowIndex)
                        if (openParen > arrowIndex + 2) {
                            // Method name is between -> and (
                            val methodNameStart = arrowIndex + 2
                            val methodName = line.substring(methodNameStart, openParen)
                            
                            // Test definition at method name position
                            val cursorPos = methodNameStart + methodName.length / 2
                            val position = Position(lineNum, cursorPos)
                            
                            val definition = definitionProvider.findDefinition(fileInfo.uri, position)
                            val hover = hoverProvider.provideHover(fileInfo.uri, position)
                            
                            tested++
                            
                            // Extract class name to categorize SDK vs workspace
                            val classMatch = Regex("""(L[^;]+;)->""").find(line)
                            val className = classMatch?.groups?.get(1)?.value ?: ""
                            val isSDK = className.startsWith("Ljava/") || className.startsWith("Ljavax/") || 
                                        className.startsWith("Landroid/") || className.startsWith("Ldalvik/") ||
                                        className.startsWith("Lkotlin/") || className.startsWith("Lkotlinx/") ||
                                        className.startsWith("Lsun/")
                            
                            if (definition.isEmpty()) {
                                invokeFailures++
                                if (!isSDK && failureExamples.size < 50) {
                                    failureExamples.add("""
                                        File: ${fileInfo.path.substringAfterLast("mastodon_decompiled/")}
                                        Line ${lineNum + 1}: ${line.trim()}
                                        Class: $className
                                        Method: $methodName
                                        Cursor: char $cursorPos
                                        Hover: ${if (hover != null) "✓" else "✗"}
                                        Definition: ✗
                                    """.trimIndent())
                                }
                            } else {
                                invokeSuccesses++
                            }
                            
                            // Stop after 2000 invoke tests
                            if (tested >= 2000) return@forEachIndexed
                        }
                    }
                }
            }
            
            if (tested >= 2000) break
        }
        
        // failureExamples now only contains workspace failures (collected above)
        val workspaceFailures = failureExamples.size
        val sdkFailures = invokeFailures - workspaceFailures
        
        println("\n=== RESULTS ===")
        println("Tested: $tested invoke instructions")
        println("Success: $invokeSuccesses (${invokeSuccesses * 100 / tested.coerceAtLeast(1)}%)")
        println("Failures: $invokeFailures (${invokeFailures * 100 / tested.coerceAtLeast(1)}%)")
        println("\nFailure breakdown:")
        println("  SDK failures: $sdkFailures (expected)")
        println("  Workspace failures: $workspaceFailures (NEED FIX)")
        
        val workspaceSuccess = (invokeSuccesses * 100.0) / (tested - sdkFailures).coerceAtLeast(1)
        println("\nWorkspace-only success: ${String.format("%.1f", workspaceSuccess)}%")
        
        println("\n=== WORKSPACE FAILURE EXAMPLES (need fix, up to 50) ===")
        failureExamples.forEach { println(it); println() }
        
        // Additional diagnostic: Check what the AST sees
        println("\n=== AST DIAGNOSTIC ===")
        if (failureExamples.isNotEmpty()) {
            println("Checking first failure in detail...")
            val firstFailure = failureExamples.first()
            val fileName = firstFailure.lines().first().substringAfter("File: ")
            val lineNum = firstFailure.lines()[1].substringAfter("Line ").substringBefore(":").toInt() - 1
            val line = firstFailure.lines()[2].substringAfter(": ")
            
            // Find the file
            val fileInfo = parsedFiles.find { it.path.endsWith(fileName) }
            if (fileInfo != null) {
                println("File found: ${fileInfo.path}")
                
                // Reparse to get AST
                val content = File(fileInfo.path).readText()
                val smaliFile = parser.parse(fileInfo.uri, content)
                
                if (smaliFile != null) {
                    // Check what node is at this position
                    val arrowIndex = line.indexOf("->")
                    val methodNameStart = arrowIndex + 2
                    val cursorPos = methodNameStart + 3 // Middle of method name
                    
                    val position = Position(lineNum, cursorPos)
                    val node = smaliFile.findNodeAt(position)
                    
                    println("Position: line=$lineNum, char=$cursorPos")
                    println("Node type: ${node?.first}")
                    println("Node: ${node?.second}")
                    
                    if (node != null && node.second is xyz.surendrajat.smalilsp.core.Instruction) {
                        val instruction = node.second as xyz.surendrajat.smalilsp.core.Instruction
                        println("Instruction type: ${instruction::class.simpleName}")
                        
                        if (instruction is xyz.surendrajat.smalilsp.core.InvokeInstruction) {
                            println("InvokeInstruction details:")
                            println("  className: ${instruction.className}")
                            println("  methodName: ${instruction.methodName}")
                            println("  descriptor: ${instruction.descriptor}")
                            
                            // Try to find this method in index
                            val foundMethods = workspaceIndex.findMethod(
                                instruction.className,
                                instruction.methodName,
                                instruction.descriptor
                            )
                            println("  Found in index: ${foundMethods.size} matches")
                            foundMethods.forEach { loc ->
                                println("    -> ${loc.uri.substringAfterLast("mastodon_decompiled/")}")
                            }
                        }
                    }
                }
            }
        }
    }
    
    data class ParsedFileInfo(val uri: String, val path: String, val className: String)
}
