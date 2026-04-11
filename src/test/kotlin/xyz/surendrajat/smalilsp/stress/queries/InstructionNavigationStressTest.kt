package xyz.surendrajat.smalilsp.stress.queries

import xyz.surendrajat.smalilsp.shared.TestUtils

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
 * COMPREHENSIVE navigation stress test for instruction-level goto definition.
 * 
 * Tests navigation on REAL instructions from all 48K+ files:
 * - Sample instructions across all types
 * - Measure navigation success rate
 * - Validate performance at scale
 * - Find edge cases and bugs
 * 
 * DISABLED by default - heavy CPU usage.
 */
@Disabled("Heavy stress test - run manually only")
class InstructionNavigationStressTest {
    
    data class NavigationResult(
        val success: Boolean,
        val instructionType: String,
        val targetClass: String,
        val file: String,
        val line: Int
    )
    
    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    fun `comprehensive instruction navigation stress test`() = runBlocking {
        println("\n" + "=".repeat(80))
        println("COMPREHENSIVE INSTRUCTION NAVIGATION STRESS TEST")
        println("Testing goto definition for instructions across ALL 48K+ files")
        println("=".repeat(80) + "\n")
        
        // Find all APK directories
        val apkDirs = listOfNotNull(
            TestUtils.getProtonMailApk(),
            TestUtils.getMastodonApk()
        ).filter { it.exists() }
        
        if (apkDirs.isEmpty()) {
            println("❌ No APK directories found. Skipping navigation stress test.")
            return@runBlocking
        }
        
        println("Found ${apkDirs.size} APK directories\n")
        
        // Collect all smali files
        val allSmaliFiles = mutableListOf<File>()
        for (apkDir in apkDirs) {
            val files = apkDir.walkTopDown()
                .filter { it.isFile && it.extension == "smali" }
                .toList()
            allSmaliFiles.addAll(files)
        }
        
        println("Total files: ${allSmaliFiles.size}\n")
        println("=".repeat(80))
        
        // Phase 1: Build workspace index
        println("\nPHASE 1: BUILDING WORKSPACE INDEX")
        println("-".repeat(80))
        
        val parser = SmaliParser()
        val workspaceIndex = WorkspaceIndex()
        val startTime = System.currentTimeMillis()
        
        var filesIndexed = 0
        for (file in allSmaliFiles) {
            filesIndexed++
            
            if (filesIndexed % 5000 == 0) {
                val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                val rate = filesIndexed / elapsed
                println("  Indexed: $filesIndexed/${allSmaliFiles.size} files " +
                        "(${String.format("%.0f", rate)} files/sec)")
            }
            
            try {
                val content = file.readText()
                val parsedFile = parser.parse(file.toURI().toString(), content)
                if (parsedFile != null) {
                    workspaceIndex.indexFile(parsedFile)
                }
            } catch (e: Exception) {
                // Ignore parse failures
            }
        }
        
        val indexTime = (System.currentTimeMillis() - startTime) / 1000.0
        
        println("\n  ✓ Indexed $filesIndexed files in ${String.format("%.1f", indexTime)}s")
        println("  ✓ Classes: $filesIndexed")
        println("  ✓ Indexing rate: ${String.format("%.0f", filesIndexed / indexTime)} files/sec")
        
        // Phase 2: Sample instructions and test navigation
        println("\n" + "=".repeat(80))
        println("PHASE 2: SAMPLING INSTRUCTIONS AND TESTING NAVIGATION")
        println("-".repeat(80))
        println()
        
        val definitionProvider = DefinitionProvider(workspaceIndex)
        val sampleSize = 10000 // Test 10K random instructions
        val results = mutableListOf<NavigationResult>()
        
        // Collect instructions from parsed files
        val instructionSamples = mutableListOf<Triple<SmaliFile, MethodDefinition, Instruction>>()
        
        var filesScanned = 0
        for (file in allSmaliFiles.shuffled().take(5000)) { // Sample from 5K files
            filesScanned++
            
            if (filesScanned % 500 == 0) {
                println("  Scanning file $filesScanned/5000 for instructions...")
            }
            
            try {
                val content = file.readText()
                val parsedFile = parser.parse(file.toURI().toString(), content)
                
                if (parsedFile != null) {
                    for (method in parsedFile.methods) {
                        for (instruction in method.instructions) {
                            instructionSamples.add(Triple(parsedFile, method, instruction))
                            
                            // Stop once we have enough samples
                            if (instructionSamples.size >= sampleSize) {
                                break
                            }
                        }
                        if (instructionSamples.size >= sampleSize) break
                    }
                }
                
                if (instructionSamples.size >= sampleSize) break
            } catch (e: Exception) {
                // Ignore
            }
        }
        
        println("\n  ✓ Collected ${instructionSamples.size} instruction samples")
        println("\n  Testing navigation for each instruction...")
        println()
        
        val navStartTime = System.currentTimeMillis()
        var tested = 0
        var invokeTests = 0
        var fieldTests = 0
        var typeTests = 0
        
        for ((parsedFile, method, instruction) in instructionSamples) {
            tested++
            
            if (tested % 1000 == 0) {
                val navElapsed = (System.currentTimeMillis() - navStartTime) / 1000.0
                val navRate = tested / navElapsed
                val successRate = results.count { it.success } * 100.0 / results.size
                println("  Progress: $tested/${instructionSamples.size} instructions " +
                        "(${String.format("%.0f", navRate)} nav/sec, " +
                        "${String.format("%.1f", successRate)}% success)")
            }
            
            try {
                // Test navigation at the instruction position
                val position = Position(
                    instruction.range.start.line,
                    instruction.range.start.character + 5
                )
                
                val locations = definitionProvider.findDefinition(parsedFile.uri, position)
                
                val instructionType = when (instruction) {
                    is InvokeInstruction -> {
                        invokeTests++
                        "invoke-${instruction.opcode}"
                    }
                    is FieldAccessInstruction -> {
                        fieldTests++
                        "field-${instruction.opcode}"
                    }
                    is TypeInstruction -> {
                        typeTests++
                        "type-${instruction.opcode}"
                    }
                    is JumpInstruction -> {
                        // JumpInstructions reference labels, not classes - skip
                        "jump-${instruction.opcode}"
                    }
                    is ConstStringInstruction -> {
                        "string-const-string"
                    }
                }
                
                val targetClass = when (instruction) {
                    is InvokeInstruction -> instruction.className
                    is FieldAccessInstruction -> instruction.className
                    is TypeInstruction -> instruction.className
                    is JumpInstruction -> "" // JumpInstructions reference labels, not classes
                    is ConstStringInstruction -> "" // String literals don't reference classes
                }
                
                results.add(NavigationResult(
                    success = locations.isNotEmpty(),
                    instructionType = instructionType,
                    targetClass = targetClass,
                    file = parsedFile.uri,
                    line = instruction.range.start.line
                ))
            } catch (e: Exception) {
                // Navigation failed
                results.add(NavigationResult(
                    success = false,
                    instructionType = "unknown",
                    targetClass = "unknown",
                    file = parsedFile.uri,
                    line = instruction.range.start.line
                ))
            }
        }
        
        val navTime = (System.currentTimeMillis() - navStartTime) / 1000.0
        
        // Phase 3: Analyze results
        println("\n" + "=".repeat(80))
        println("PHASE 3: NAVIGATION RESULTS ANALYSIS")
        println("=".repeat(80))
        println()
        
        val totalTests = results.size
        val successful = results.count { it.success }
        val failed = results.count { !it.success }
        val successRate = successful * 100.0 / totalTests
        
        println("Total navigation tests:     $totalTests")
        println("Successful navigations:     $successful (${String.format("%.2f", successRate)}%)")
        println("Failed navigations:         $failed (${String.format("%.2f", failed * 100.0 / totalTests)}%)")
        println()
        
        println("Performance:")
        println("  Time:                     ${String.format("%.1f", navTime)}s")
        println("  Navigation rate:          ${String.format("%.0f", totalTests / navTime)} nav/sec")
        println("  Average per navigation:   ${String.format("%.2f", navTime * 1000 / totalTests)}ms")
        println()
        
        // Breakdown by instruction type
        println("-".repeat(80))
        println("SUCCESS RATE BY INSTRUCTION TYPE")
        println("-".repeat(80))
        println()
        
        val invokeResults = results.filter { it.instructionType.startsWith("invoke-") }
        val invokeSuccess = invokeResults.count { it.success }
        val invokeSuccessRate = if (invokeResults.isNotEmpty()) 
            invokeSuccess * 100.0 / invokeResults.size else 0.0
        
        val fieldResults = results.filter { it.instructionType.startsWith("field-") }
        val fieldSuccess = fieldResults.count { it.success }
        val fieldSuccessRate = if (fieldResults.isNotEmpty()) 
            fieldSuccess * 100.0 / fieldResults.size else 0.0
        
        val typeResults = results.filter { it.instructionType.startsWith("type-") }
        val typeSuccess = typeResults.count { it.success }
        val typeSuccessRate = if (typeResults.isNotEmpty()) 
            typeSuccess * 100.0 / typeResults.size else 0.0
        
        println("INVOKE INSTRUCTIONS:")
        println("  Total tested:             ${invokeResults.size}")
        println("  Successful:               $invokeSuccess (${String.format("%.2f", invokeSuccessRate)}%)")
        println("  Failed:                   ${invokeResults.size - invokeSuccess}")
        println()
        
        println("FIELD ACCESS INSTRUCTIONS:")
        println("  Total tested:             ${fieldResults.size}")
        println("  Successful:               $fieldSuccess (${String.format("%.2f", fieldSuccessRate)}%)")
        println("  Failed:                   ${fieldResults.size - fieldSuccess}")
        println()
        
        println("TYPE INSTRUCTIONS:")
        println("  Total tested:             ${typeResults.size}")
        println("  Successful:               $typeSuccess (${String.format("%.2f", typeSuccessRate)}%)")
        println("  Failed:                   ${typeResults.size - typeSuccess}")
        println()
        
        // Analyze failures
        println("-".repeat(80))
        println("FAILURE ANALYSIS")
        println("-".repeat(80))
        println()
        
        val failedResults = results.filter { !it.success }
        val failuresByType = failedResults.groupBy { it.instructionType }
        
        println("Failures by instruction type:")
        for ((type, failures) in failuresByType.entries.sortedByDescending { it.value.size }) {
            println("  $type: ${failures.size}")
        }
        println()
        
        // Sample failed targets (SDK vs workspace)
        val failedTargets = failedResults.map { it.targetClass }.distinct()
        val sdkFailures = failedTargets.count { 
            it.startsWith("Ljava/") || it.startsWith("Landroid/") || 
            it.startsWith("Ljavax/") || it.startsWith("Ldalvik/")
        }
        val workspaceFailures = failedTargets.size - sdkFailures
        
        println("Failed target analysis:")
        println("  SDK classes (expected):   $sdkFailures")
        println("  Workspace classes:        $workspaceFailures")
        println()
        
        if (workspaceFailures > 0) {
            println("Sample workspace failures (should investigate):")
            failedTargets
                .filter { !it.startsWith("Ljava/") && !it.startsWith("Landroid/") && 
                          !it.startsWith("Ljavax/") && !it.startsWith("Ldalvik/") }
                .take(10)
                .forEach { println("  - $it") }
            println()
        }
        
        // Validation
        println("=".repeat(80))
        println("VALIDATION")
        println("=".repeat(80))
        println()
        
        // Overall success rate should be high (accounting for SDK classes)
        println("✓ Overall success rate: ${String.format("%.2f", successRate)}%")
        
        // For workspace classes (non-SDK), success should be very high
        val workspaceResults = results.filter { result ->
            !result.targetClass.startsWith("Ljava/") && 
            !result.targetClass.startsWith("Landroid/") &&
            !result.targetClass.startsWith("Ljavax/") && 
            !result.targetClass.startsWith("Ldalvik/") &&
            !result.targetClass.startsWith("Lcom/google/") &&
            !result.targetClass.startsWith("Landroidx/")
        }
        
        if (workspaceResults.isNotEmpty()) {
            val workspaceSuccess = workspaceResults.count { it.success }
            val workspaceSuccessRate = workspaceSuccess * 100.0 / workspaceResults.size
            println("✓ Workspace-only success rate: ${String.format("%.2f", workspaceSuccessRate)}%")
            
            // Workspace success should be very high (>80%)
            assertTrue(workspaceSuccessRate >= 80.0, 
                "Workspace navigation success rate too low: $workspaceSuccessRate%")
        }
        
        // Navigation should be fast (<10ms average)
        val avgNavTime = navTime * 1000 / totalTests
        println("✓ Average navigation time: ${String.format("%.2f", avgNavTime)}ms")
        assertTrue(avgNavTime < 10.0, 
            "Navigation too slow: ${avgNavTime}ms average")
        
        println()
        println("✅ ALL VALIDATION CHECKS PASSED")
        println()
        println("=".repeat(80))
        println("INSTRUCTION NAVIGATION: PRODUCTION READY")
        println("=".repeat(80))
    }
}
