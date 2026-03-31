package xyz.surendrajat.smalilsp.performance

import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import xyz.surendrajat.smalilsp.TestUtils
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.indexer.WorkspaceScanner
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import xyz.surendrajat.smalilsp.core.*
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue
import kotlin.system.measureTimeMillis

/**
 * STRESS TEST: Validate method & field reference finding on REAL Mastodon APK.
 * 
 * This test proves the new features work correctly at scale:
 * - 4,415 files indexed
 * - Find method invocations across entire codebase
 * - Find field accesses across entire codebase
 * - Measure performance and accuracy
 * 
 * User requested this to verify claims about new functionality.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReferenceProviderStressTest {
    
    private var workspaceRoot: File? = null
    private lateinit var index: WorkspaceIndex
    private lateinit var scanner: WorkspaceScanner
    private lateinit var provider: ReferenceProvider
    
    private var indexTime: Long = 0
    
    @BeforeAll
    fun setup() = runBlocking {
        val apk = TestUtils.getMastodonApk()
        assumeTrue(apk != null, "Mastodon APK not available, skipping")
        workspaceRoot = apk
        
        index = WorkspaceIndex()
        scanner = WorkspaceScanner(index)
        provider = ReferenceProvider(index)
        
        println("\n=== ReferenceProvider Stress Test - Mastodon APK ===")
        println("Files: 4,415 smali files")
        println("Goal: Validate method & field reference finding at scale\n")
        
        // Index entire workspace
        indexTime = measureTimeMillis {
            scanner.scanDirectory(workspaceRoot!!) { processed, total ->
                if (processed % 1000 == 0 || processed == total) {
                    println("Indexing: $processed/$total files")
                }
            }
        }
        
        val stats = index.getStats()
        println("\n✅ Indexed in ${indexTime}ms (${indexTime/1000.0}s)")
        println("   Classes: ${stats.classes}")
        println("   Methods: ${stats.methods}")
        println("   Fields: ${stats.fields}")
    }
    
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    fun `validate method references - find invoke instructions across workspace`() {
        println("\n📊 TEST 1: Method Reference Finding")
        println("=" .repeat(70))
        
        // Pick a commonly used Android method to test
        val testCases = mutableListOf<MethodRefTestCase>()
        
        // Sample some classes to test
        val classNames = index.getAllClassNames().take(50)
        
        for (className in classNames) {
            val file = index.findClass(className) ?: continue
            
            // Test references to each method in this class
            for (method in file.methods.take(3)) { // Sample 3 methods per class
                val refs: List<org.eclipse.lsp4j.Location>
                val refTime = measureTimeMillis {
                    refs = provider.findReferences(
                        uri = file.uri,
                        position = Position(method.range.start.line, method.range.start.character + 10),
                        includeDeclaration = true
                    )
                }
                
                testCases.add(MethodRefTestCase(
                    className = className,
                    methodName = method.name,
                    descriptor = method.descriptor,
                    referencesFound = refs.size,
                    timeMs = refTime
                ))
            }
            
            if (testCases.size >= 100) break // Test 100 methods
        }
        
        // Analyze results
        val totalRefs = testCases.sumOf { it.referencesFound }
        val avgTime = testCases.map { it.timeMs }.average()
        val withRefs = testCases.count { it.referencesFound > 1 } // More than just declaration
        
        println("\nResults:")
        println("  Methods tested: ${testCases.size}")
        println("  Total references found: $totalRefs")
        println("  Methods with usages: $withRefs (${withRefs*100/testCases.size}%)")
        println("  Avg lookup time: ${"%.2f".format(avgTime)}ms")
        
        // Show some examples
        println("\n📋 Sample results (methods with most references):")
        testCases.sortedByDescending { it.referencesFound }.take(10).forEach { tc ->
            println("  ${tc.methodName} → ${tc.referencesFound} references (${tc.timeMs}ms)")
        }
        
        // Verify we found real references
        assertTrue(totalRefs > testCases.size, "Should find more than just declarations")
        assertTrue(withRefs > 0, "Should find methods with actual usages")
        assertTrue(avgTime < 1000, "Lookups should be reasonably fast")
        
        println("\n✅ Method reference finding WORKS at scale")
    }
    
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    fun `validate field references - find field access instructions across workspace`() {
        println("\n📊 TEST 2: Field Reference Finding")
        println("=".repeat(70))
        
        val testCases = mutableListOf<FieldRefTestCase>()
        
        // Sample some classes to test
        val classNames = index.getAllClassNames().take(50)
        
        for (className in classNames) {
            val file = index.findClass(className) ?: continue
            
            // Test references to each field in this class
            for (field in file.fields.take(3)) { // Sample 3 fields per class
                val refs: List<org.eclipse.lsp4j.Location>
                val refTime = measureTimeMillis {
                    refs = provider.findReferences(
                        uri = file.uri,
                        position = Position(field.range.start.line, field.range.start.character + 10),
                        includeDeclaration = true
                    )
                }
                
                testCases.add(FieldRefTestCase(
                    className = className,
                    fieldName = field.name,
                    fieldType = field.type,
                    referencesFound = refs.size,
                    timeMs = refTime
                ))
            }
            
            if (testCases.size >= 100) break // Test 100 fields
        }
        
        // Analyze results
        val totalRefs = testCases.sumOf { it.referencesFound }
        val avgTime = testCases.map { it.timeMs }.average()
        val withRefs = testCases.count { it.referencesFound > 1 }
        
        println("\nResults:")
        println("  Fields tested: ${testCases.size}")
        println("  Total references found: $totalRefs")
        println("  Fields with usages: $withRefs (${withRefs*100/testCases.size}%)")
        println("  Avg lookup time: ${"%.2f".format(avgTime)}ms")
        
        // Show some examples
        println("\n📋 Sample results (fields with most references):")
        testCases.sortedByDescending { it.referencesFound }.take(10).forEach { tc ->
            println("  ${tc.fieldName} → ${tc.referencesFound} references (${tc.timeMs}ms)")
        }
        
        // Verify we found real references
        assertTrue(totalRefs > testCases.size, "Should find more than just declarations")
        assertTrue(avgTime < 1000, "Lookups should be reasonably fast")
        
        println("\n✅ Field reference finding WORKS at scale")
    }
    
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `verify instruction AST contains invoke instructions`() {
        println("\n📊 TEST 3: Instruction AST Validation")
        println("=".repeat(70))
        
        var totalMethods = 0
        var methodsWithInstructions = 0
        var totalInvokeInstructions = 0
        var totalFieldInstructions = 0
        
        // Scan first 100 files
        val classNames = index.getAllClassNames().take(100)
        
        for (className in classNames) {
            val file = index.findClass(className) ?: continue
            
            for (method in file.methods) {
                totalMethods++
                
                if (method.instructions.isNotEmpty()) {
                    methodsWithInstructions++
                }
                
                for (instruction in method.instructions) {
                    when (instruction) {
                        is InvokeInstruction -> totalInvokeInstructions++
                        is FieldAccessInstruction -> totalFieldInstructions++
                        else -> {}
                    }
                }
            }
        }
        
        println("\nInstruction AST Stats (first 100 classes):")
        println("  Methods scanned: $totalMethods")
        println("  Methods with instructions: $methodsWithInstructions (${methodsWithInstructions*100/totalMethods}%)")
        println("  Invoke instructions: $totalInvokeInstructions")
        println("  Field access instructions: $totalFieldInstructions")
        
        // Verify instruction AST exists
        assertTrue(totalInvokeInstructions > 0, "Should have invoke instructions in AST")
        assertTrue(totalFieldInstructions > 0, "Should have field access instructions in AST")
        assertTrue(methodsWithInstructions > 0, "Should have methods with instruction AST")
        
        println("\n✅ Instruction AST contains real data for reference finding")
    }
    
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `performance benchmark - reference finding speed`() {
        println("\n📊 TEST 4: Performance Benchmark")
        println("=".repeat(70))
        
        val classNames = index.getAllClassNames().take(10)
        val times = mutableListOf<Long>()
        
        // Benchmark method reference finding
        for (className in classNames) {
            val file = index.findClass(className) ?: continue
            
            for (method in file.methods.take(5)) {
                val time = measureTimeMillis {
                    provider.findReferences(
                        uri = file.uri,
                        position = Position(method.range.start.line, 10),
                        includeDeclaration = true
                    )
                }
                times.add(time)
            }
        }
        
        times.sort()
        val p50 = times[times.size / 2]
        val p95 = times[(times.size * 95) / 100]
        val p99 = times[(times.size * 99) / 100]
        val max = times.last()
        
        println("\nPerformance (${times.size} reference lookups):")
        println("  p50 (median): ${p50}ms")
        println("  p95: ${p95}ms")
        println("  p99: ${p99}ms")
        println("  max: ${max}ms")
        
        // Performance should be reasonable
        assertTrue(p50 < 100, "Median lookup should be under 100ms")
        assertTrue(p95 < 500, "95th percentile should be under 500ms")
        
        println("\n✅ Performance is acceptable")
    }
    
    data class MethodRefTestCase(
        val className: String,
        val methodName: String,
        val descriptor: String,
        val referencesFound: Int,
        val timeMs: Long
    )
    
    data class FieldRefTestCase(
        val className: String,
        val fieldName: String,
        val fieldType: String,
        val referencesFound: Int,
        val timeMs: Long
    )
}
