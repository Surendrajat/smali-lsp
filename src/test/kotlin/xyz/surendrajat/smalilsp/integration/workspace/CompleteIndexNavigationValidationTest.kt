package xyz.surendrajat.smalilsp.integration.workspace

import xyz.surendrajat.smalilsp.TestUtils

import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import xyz.surendrajat.smalilsp.core.*
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

import xyz.surendrajat.smalilsp.core.SmaliFile
/**
 * PROPER instruction navigation validation with COMPLETE index.
 * 
 * Strategy:
 * 1. Index ALL 48K+ files (gives complete workspace coverage)
 * 2. Sample instructions from subset of files
 * 3. Test navigation (now all workspace classes will be in index)
 * 4. Measure TRUE success rate
 */
@Disabled("Heavy validation test - indexes 48K+ files - run manually only")
class CompleteIndexNavigationValidationTest {
    
    data class NavigationStats(
        var totalTests: Int = 0,
        var totalSuccess: Int = 0,
        var totalFailed: Int = 0,
        
        // By instruction type
        var invokeTests: Int = 0,
        var invokeSuccess: Int = 0,
        var fieldTests: Int = 0,
        var fieldSuccess: Int = 0,
        var typeTests: Int = 0,
        var typeSuccess: Int = 0,
        
        // By target type
        var sdkTargetFailed: Int = 0,
        var workspaceTargetFailed: Int = 0,
        var externalLibTargetFailed: Int = 0,
        var unknownTargetFailed: Int = 0,
        
        // Performance
        var totalNavTimeMs: Long = 0
    )
    
    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `validate instruction navigation with complete workspace index`() = runBlocking {
        println("\n" + "=".repeat(80))
        println("COMPLETE WORKSPACE INDEX NAVIGATION VALIDATION")
        println("=".repeat(80) + "\n")
        
        // Find all APK directories
        val apkDirs = listOfNotNull(
            TestUtils.getProtonMailApk(),
            TestUtils.getMastodonApk()
        ).filter { it.exists() }
        
        if (apkDirs.isEmpty()) {
            println("❌ No APK directories found")
            return@runBlocking
        }
        
        // Collect ALL files
        val allFiles = mutableListOf<File>()
        for (apkDir in apkDirs) {
            val files = apkDir.walkTopDown()
                .filter { it.isFile && it.extension == "smali" }
                .toList()
            allFiles.addAll(files)
        }
        
        println("Found ${allFiles.size} total files\n")
        println("=".repeat(80))
        
        // Phase 1: Index ALL files (complete workspace)
        println("\nPHASE 1: INDEXING ALL FILES")
        println("-".repeat(80))
        
        val parser = SmaliParser()
        val workspaceIndex = WorkspaceIndex()
        
        val indexStart = System.currentTimeMillis()
        var indexed = 0
        
        for (file in allFiles) {
            indexed++
            
            if (indexed % 5000 == 0) {
                val elapsed = (System.currentTimeMillis() - indexStart) / 1000.0
                val rate = indexed / elapsed
                val eta = ((allFiles.size - indexed) / rate).toInt()
                println("  Progress: $indexed/${allFiles.size} " +
                        "(${String.format("%.0f", rate)} files/sec, ETA: ${eta}s)")
            }
            
            try {
                val content = file.readText()
                val parsed = parser.parse(file.toURI().toString(), content)
                if (parsed != null) {
                    workspaceIndex.indexFile(parsed)
                }
            } catch (e: Exception) {
                // Skip parse failures
            }
        }
        
        val indexTime = (System.currentTimeMillis() - indexStart) / 1000.0
        
        println("\n  ✓ Indexed ${indexed} files in ${String.format("%.1f", indexTime)}s")
        println("  ✓ Rate: ${String.format("%.0f", indexed / indexTime)} files/sec")
        
        // Phase 2: Sample instructions from subset of files for testing
        println("\n" + "=".repeat(80))
        println("PHASE 2: SAMPLING INSTRUCTIONS FOR TESTING")
        println("-".repeat(80))
        
        // Sample 1000 files for instruction extraction
        val sampledFiles = allFiles.shuffled().take(1000)
        val parsedSamples = mutableListOf<SmaliFile>()
        
        for (file in sampledFiles) {
            try {
                val content = file.readText()
                val parsed = parser.parse(file.toURI().toString(), content)
                if (parsed != null) {
                    parsedSamples.add(parsed)
                }
            } catch (e: Exception) {
                // Skip
            }
        }
        
        println("  ✓ Sampled ${parsedSamples.size} files for testing")
        
        // Extract instructions
        data class InstructionSample(
            val file: SmaliFile,
            val method: MethodDefinition,
            val instruction: Instruction
        )
        
        val instructions = mutableListOf<InstructionSample>()
        
        for (file in parsedSamples) {
            for (method in file.methods) {
                for (instruction in method.instructions) {
                    instructions.add(InstructionSample(file, method, instruction))
                }
            }
        }
        
        println("  ✓ Extracted ${instructions.size} instructions")
        
        val invokeCount = instructions.count { it.instruction is InvokeInstruction }
        val fieldCount = instructions.count { it.instruction is FieldAccessInstruction }
        val typeCount = instructions.count { it.instruction is TypeInstruction }
        
        println("  ✓ Invoke: $invokeCount")
        println("  ✓ Field: $fieldCount")
        println("  ✓ Type: $typeCount")
        
        // Phase 3: Test navigation for ALL instructions
        println("\n" + "=".repeat(80))
        println("PHASE 3: TESTING NAVIGATION")
        println("-".repeat(80))
        println()
        
        val definitionProvider = DefinitionProvider(workspaceIndex)
        val stats = NavigationStats()
        val navStart = System.currentTimeMillis()
        
        var tested = 0
        for (sample in instructions) {
            tested++
            
            if (tested % 2000 == 0) {
                val elapsed = (System.currentTimeMillis() - navStart) / 1000.0
                val rate = if (elapsed > 0) tested / elapsed else 0.0
                val successRate = if (stats.totalTests > 0) 
                    stats.totalSuccess * 100.0 / stats.totalTests else 0.0
                println("  Progress: $tested/${instructions.size} " +
                        "(${String.format("%.0f", rate)} nav/sec, " +
                        "${String.format("%.1f", successRate)}% success)")
            }
            
            stats.totalTests++
            
            try {
                // Navigate from instruction
                val position = Position(
                    sample.instruction.range.start.line,
                    sample.instruction.range.start.character + 5
                )
                
                val navStartMs = System.currentTimeMillis()
                val locations = definitionProvider.findDefinition(sample.file.uri, position)
                stats.totalNavTimeMs += (System.currentTimeMillis() - navStartMs)
                
                val success = locations.isNotEmpty()
                
                when (sample.instruction) {
                    is InvokeInstruction -> {
                        stats.invokeTests++
                        if (success) stats.invokeSuccess++
                        else analyzeFailure(sample.instruction.className, stats)
                    }
                    is FieldAccessInstruction -> {
                        stats.fieldTests++
                        if (success) stats.fieldSuccess++
                        else analyzeFailure(sample.instruction.className, stats)
                    }
                    is TypeInstruction -> {
                        stats.typeTests++
                        if (success) stats.typeSuccess++
                        else analyzeFailure(sample.instruction.className, stats)
                    }
                    is JumpInstruction -> {
                        // JumpInstructions reference labels, not classes - skip
                    }
                }
                
                if (success) stats.totalSuccess++
                else stats.totalFailed++
                
            } catch (e: Exception) {
                stats.totalFailed++
            }
        }
        
        val navTime = (System.currentTimeMillis() - navStart) / 1000.0
        
        // Phase 4: Report results
        println("\n" + "=".repeat(80))
        println("RESULTS: NAVIGATION WITH COMPLETE INDEX")
        println("=".repeat(80))
        println()
        
        println("OVERALL STATISTICS")
        println("-".repeat(80))
        val overallSuccess = stats.totalSuccess * 100.0 / stats.totalTests
        println("  Total tests:              ${stats.totalTests}")
        println("  Successful:               ${stats.totalSuccess} (${String.format("%.2f", overallSuccess)}%)")
        println("  Failed:                   ${stats.totalFailed} (${String.format("%.2f", stats.totalFailed * 100.0 / stats.totalTests)}%)")
        println()
        
        println("BY INSTRUCTION TYPE")
        println("-".repeat(80))
        
        val invokeSuccessRate = if (stats.invokeTests > 0) 
            stats.invokeSuccess * 100.0 / stats.invokeTests else 0.0
        println("  INVOKE INSTRUCTIONS:")
        println("    Tested:                 ${stats.invokeTests}")
        println("    Successful:             ${stats.invokeSuccess} (${String.format("%.2f", invokeSuccessRate)}%)")
        println("    Failed:                 ${stats.invokeTests - stats.invokeSuccess}")
        println()
        
        val fieldSuccessRate = if (stats.fieldTests > 0) 
            stats.fieldSuccess * 100.0 / stats.fieldTests else 0.0
        println("  FIELD ACCESS INSTRUCTIONS:")
        println("    Tested:                 ${stats.fieldTests}")
        println("    Successful:             ${stats.fieldSuccess} (${String.format("%.2f", fieldSuccessRate)}%)")
        println("    Failed:                 ${stats.fieldTests - stats.fieldSuccess}")
        println()
        
        val typeSuccessRate = if (stats.typeTests > 0) 
            stats.typeSuccess * 100.0 / stats.typeTests else 0.0
        println("  TYPE INSTRUCTIONS:")
        println("    Tested:                 ${stats.typeTests}")
        println("    Successful:             ${stats.typeSuccess} (${String.format("%.2f", typeSuccessRate)}%)")
        println("    Failed:                 ${stats.typeTests - stats.typeSuccess}")
        println()
        
        println("FAILURE ANALYSIS")
        println("-".repeat(80))
        val totalFailures = stats.totalFailed
        if (totalFailures > 0) {
            println("  SDK classes (expected):   ${stats.sdkTargetFailed} " +
                    "(${String.format("%.1f", stats.sdkTargetFailed * 100.0 / totalFailures)}%)")
            println("  External libs (expected): ${stats.externalLibTargetFailed} " +
                    "(${String.format("%.1f", stats.externalLibTargetFailed * 100.0 / totalFailures)}%)")
            println("  Workspace classes (BUG):  ${stats.workspaceTargetFailed} " +
                    "(${String.format("%.1f", stats.workspaceTargetFailed * 100.0 / totalFailures)}%)")
            println("  Unknown targets:          ${stats.unknownTargetFailed} " +
                    "(${String.format("%.1f", stats.unknownTargetFailed * 100.0 / totalFailures)}%)")
        }
        println()
        
        println("PERFORMANCE")
        println("-".repeat(80))
        val avgNavMs = stats.totalNavTimeMs.toDouble() / stats.totalTests
        val navsPerSec = stats.totalTests / navTime
        println("  Total navigation time:    ${String.format("%.1f", navTime)}s")
        println("  Average per navigation:   ${String.format("%.2f", avgNavMs)}ms")
        println("  Navigations per second:   ${String.format("%.0f", navsPerSec)}")
        println()
        
        // Validation
        println("=".repeat(80))
        println("VALIDATION")
        println("=".repeat(80))
        println()
        
        // Should find lots of instructions
        println("✓ Found ${instructions.size} instructions")
        assertTrue(instructions.size > 5000, 
            "Too few instructions: ${instructions.size}")
        
        // With complete index, success should be much higher
        println("✓ Overall success rate: ${String.format("%.2f", overallSuccess)}%")
        // Most failures should now be SDK/external libs
        assertTrue(overallSuccess >= 50.0, 
            "Overall success rate too low even with complete index: $overallSuccess%")
        
        // Navigation should be fast
        println("✓ Average navigation time: ${String.format("%.2f", avgNavMs)}ms")
        assertTrue(avgNavMs < 10.0, 
            "Navigation too slow: ${avgNavMs}ms")
        
        // With complete index, workspace bugs should be minimal
        if (totalFailures > 0) {
            val workspaceBugRate = stats.workspaceTargetFailed * 100.0 / totalFailures
            println("✓ Workspace navigation bugs: ${stats.workspaceTargetFailed} " +
                    "(${String.format("%.1f", workspaceBugRate)}% of failures)")
            assertTrue(workspaceBugRate < 10.0, 
                "Too many workspace navigation bugs with complete index: $workspaceBugRate%")
        }
        
        println()
        println("✅ ALL VALIDATION CHECKS PASSED")
        println()
        println("=".repeat(80))
        println("INSTRUCTION NAVIGATION: PRODUCTION READY")
        println("=".repeat(80))
    }
    
    private fun analyzeFailure(targetClass: String, stats: NavigationStats) {
        when {
            targetClass.startsWith("Ljava/") || 
            targetClass.startsWith("Landroid/") ||
            targetClass.startsWith("Ljavax/") ||
            targetClass.startsWith("Ldalvik/") -> {
                stats.sdkTargetFailed++
            }
            targetClass.startsWith("Lcom/google/") ||
            targetClass.startsWith("Landroidx/") ||
            targetClass.startsWith("Lokhttp") ||
            targetClass.startsWith("Lretrofit") ||
            targetClass.startsWith("Lkotlin/") -> {
                stats.externalLibTargetFailed++
            }
            targetClass.startsWith("L") -> {
                // Workspace class - might be a bug OR might be interface/abstract method
                stats.workspaceTargetFailed++
            }
            else -> {
                stats.unknownTargetFailed++
            }
        }
    }
}
