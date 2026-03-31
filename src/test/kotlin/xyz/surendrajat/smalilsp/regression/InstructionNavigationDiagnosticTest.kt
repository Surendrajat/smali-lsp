package xyz.surendrajat.smalilsp.regression

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.TestUtils
import xyz.surendrajat.smalilsp.core.*
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import java.io.File

import xyz.surendrajat.smalilsp.core.SmaliFile
/**
 * Diagnostic test to understand WHY workspace navigation is failing.
 * 
 * Tests:
 * 1. Are instructions parsed correctly?
 * 2. Are target classes in the index?
 * 3. Is navigation logic working?
 */
class InstructionNavigationDiagnosticTest {
    
    @Test
    fun `diagnose workspace navigation failures`() {
        println("\n" + "=".repeat(80))
        println("DIAGNOSTIC: WORKSPACE NAVIGATION FAILURES")
        println("=".repeat(80) + "\n")
        
        // Sample 100 files
        val apkDir = TestUtils.getProtonMailApk()!!
        val files = apkDir.walkTopDown()
            .filter { it.isFile && it.extension == "smali" }
            .toList()
            .shuffled()
            .take(100)
        
        println("Sampled ${files.size} files\n")
        
        // Parse and index
        val parser = SmaliParser()
        val workspaceIndex = WorkspaceIndex()
        val parsedFiles = mutableListOf<SmaliFile>()
        
        for (file in files) {
            try {
                val content = file.readText()
                val parsed = parser.parse(file.toURI().toString(), content)
                if (parsed != null) {
                    workspaceIndex.indexFile(parsed)
                    parsedFiles.add(parsed)
                }
            } catch (e: Exception) {
                // Skip
            }
        }
        
        println("Parsed and indexed: ${parsedFiles.size} files\n")
        println("=".repeat(80))
        
        // Extract all invoke instructions pointing to workspace classes
        data class InvokeSample(
            val file: SmaliFile,
            val method: MethodDefinition,
            val instruction: InvokeInstruction
        )
        
        val workspaceInvokes = mutableListOf<InvokeSample>()
        
        for (file in parsedFiles) {
            for (method in file.methods) {
                for (instruction in method.instructions) {
                    if (instruction is InvokeInstruction) {
                        // Filter to workspace classes (exclude SDK/external)
                        val targetClass = instruction.className
                        if (!targetClass.startsWith("Ljava/") &&
                            !targetClass.startsWith("Landroid/") &&
                            !targetClass.startsWith("Ljavax/") &&
                            !targetClass.startsWith("Ldalvik/") &&
                            !targetClass.startsWith("Lcom/google/") &&
                            !targetClass.startsWith("Landroidx/") &&
                            !targetClass.startsWith("Lokhttp") &&
                            !targetClass.startsWith("Lretrofit") &&
                            !targetClass.startsWith("Lkotlin/")) {
                            
                            workspaceInvokes.add(InvokeSample(file, method, instruction))
                        }
                    }
                }
            }
        }
        
        println("\nFound ${workspaceInvokes.size} invoke instructions to workspace classes")
        
        if (workspaceInvokes.isEmpty()) {
            println("❌ No workspace invokes found - sample too small")
            return
        }
        
        // Take first 20 samples and diagnose each
        println("\n" + "=".repeat(80))
        println("DIAGNOSING FIRST 20 WORKSPACE INVOKES")
        println("=".repeat(80) + "\n")
        
        val definitionProvider = DefinitionProvider(workspaceIndex)
        
        for ((index, sample) in workspaceInvokes.take(20).withIndex()) {
            println("[$index] Diagnosing invoke in ${sample.file.classDefinition.name}")
            println("    Method: ${sample.method.name}${sample.method.descriptor}")
            println("    Instruction: ${sample.instruction.opcode}")
            println("    Target class: ${sample.instruction.className}")
            println("    Target method: ${sample.instruction.methodName}${sample.instruction.descriptor}")
            
            // Check 1: Is target class in index?
            val targetFile = workspaceIndex.findClass(sample.instruction.className)
            if (targetFile == null) {
                println("    ❌ Target class NOT in index")
                println("    Reason: Class not parsed or not in sampled files")
            } else {
                println("    ✓ Target class IN index")
                
                // Check 2: Does target class have the method?
                val targetMethod = targetFile.methods.find {
                    it.name == sample.instruction.methodName &&
                    it.descriptor == sample.instruction.descriptor
                }
                
                if (targetMethod == null) {
                    println("    ❌ Target method NOT found in class")
                    println("    Available methods in class:")
                    targetFile.methods.take(5).forEach {
                        println("        - ${it.name}${it.descriptor}")
                    }
                    if (targetFile.methods.size > 5) {
                        println("        ... and ${targetFile.methods.size - 5} more")
                    }
                } else {
                    println("    ✓ Target method found in class")
                }
            }
            
            // Check 3: Does navigation work?
            val position = Position(
                sample.instruction.range.start.line,
                sample.instruction.range.start.character + 5
            )
            
            val locations = definitionProvider.findDefinition(sample.file.uri, position)
            
            if (locations.isEmpty()) {
                println("    ❌ Navigation FAILED")
            } else {
                println("    ✓ Navigation SUCCESS (${locations.size} location(s))")
            }
            
            println()
        }
        
        // Summary
        println("=".repeat(80))
        println("DIAGNOSIS SUMMARY")
        println("=".repeat(80))
        println()
        println("Common reasons for workspace navigation failures:")
        println("1. Target class not in index (class not parsed/sampled)")
        println("2. Method descriptor mismatch (generics, inner classes)")
        println("3. Instruction parsing extracting wrong class/method names")
        println("4. Navigation logic not finding instruction node")
        println()
        println("To fix: Examine diagnostic output above and identify patterns")
    }
}
