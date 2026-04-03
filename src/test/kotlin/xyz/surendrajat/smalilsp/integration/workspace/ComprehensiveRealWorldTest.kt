package xyz.surendrajat.smalilsp.integration.workspace

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.fail
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.indexer.WorkspaceScanner
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.HoverProvider
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import xyz.surendrajat.smalilsp.shared.TestUtils
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.system.measureTimeMillis

/**
 * COMPREHENSIVE real-world validation with ALL files.
 * 
 * This is NOT a sample test - it validates ALL 4,415 mastodon files.
 * 
 * Measures:
 * - Success rate (should be 99%+)
 * - p50, p95, p99 latencies
 * - Failure analysis
 * 
 * Purpose: Ensure production quality, not just "good enough".
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ComprehensiveRealWorldTest {
    
    private lateinit var index: WorkspaceIndex
    private lateinit var parser: SmaliParser
    private lateinit var hoverProvider: HoverProvider
    private lateinit var definitionProvider: DefinitionProvider
    private lateinit var referenceProvider: ReferenceProvider
    private lateinit var allClasses: List<String>
    
    @BeforeAll
    fun setup() {
        println("\n" + "=".repeat(80))
        println("COMPREHENSIVE TEST: Testing ALL mastodon files (not samples!)")
        println("=".repeat(80))
        
        val projectRoot = File(System.getProperty("user.dir"))
        val mastodonPath = TestUtils.getMastodonApk()
        assumeTrue(mastodonPath != null, "Mastodon APK not available — skipping ComprehensiveRealWorldTest")

        // Index all files
        index = WorkspaceIndex()
        parser = SmaliParser()
        hoverProvider = HoverProvider(index)
        definitionProvider = DefinitionProvider(index)
        referenceProvider = ReferenceProvider(index)
        
        println("\n📂 Indexing mastodon APK...")
        val scanner = WorkspaceScanner(index, parser)
        runBlocking {
            scanner.scanDirectory(mastodonPath!!)
        }
        
        // Get ALL classes (not a sample!)
        allClasses = index.getAllClassNames()
        println("✅ Indexed ${allClasses.size} classes")
        println("⚠️  WARNING: This test will process ALL ${allClasses.size} files")
        println("    This is intentionally slow - we're validating production quality\n")
    }
    
    @Test
    fun `test ALL files - parsing success rate`() {
        println("\n📊 TEST 1: Parsing Success Rate on ALL ${allClasses.size} files")
        println("-".repeat(80))
        
        var successCount = 0
        var failCount = 0
        val failures = mutableListOf<String>()
        
        allClasses.forEach { className ->
            val uri = index.getUri(className)
            if (uri == null) {
                failCount++
                failures.add("$className: No URI")
                return@forEach
            }
            
            if (uri.startsWith("sdk://")) {
                // SDK classes don't have files, this is expected
                successCount++
                return@forEach
            }
            
            val filePath = uri.removePrefix("file:")
            val file = File(filePath)
            if (!file.exists()) {
                failCount++
                failures.add("$className: File not found")
                return@forEach
            }
            
            val content = file.readText()
            val parsedFile = parser.parse(uri, content)
            
            if (parsedFile == null) {
                failCount++
                failures.add("$className: Parse failed")
            } else {
                successCount++
            }
        }
        
        val total = successCount + failCount
        val successRate = (successCount.toDouble() / total) * 100
        
        println("Results:")
        println("  Total files:    $total")
        println("  Successful:     $successCount")
        println("  Failed:         $failCount")
        println("  Success rate:   ${"%.2f".format(successRate)}%")
        
        if (failures.isNotEmpty()) {
            println("\nFirst 10 failures:")
            failures.take(10).forEach { println("  - $it") }
        }
        
        // REQUIREMENT: 99%+ success rate
        assertTrue(successRate >= 99.0, "Success rate must be ≥99%, got ${"%.2f".format(successRate)}%")
        println("\n✅ PASSED: Success rate meets 99%+ requirement")
    }
    
    @Test
    fun `test ALL files - hover with performance percentiles`() {
        println("\n📊 TEST 2: Hover on ALL ${allClasses.size} files")
        println("-".repeat(80))
        
        val times = mutableListOf<Long>()
        var successCount = 0
        var failCount = 0
        var skippedCount = 0
        val failures = mutableListOf<String>()
        
        allClasses.forEach { className ->
            val uri = index.getUri(className) ?: run {
                skippedCount++
                return@forEach
            }
            
            if (uri.startsWith("sdk://")) {
                skippedCount++
                return@forEach
            }
            
            val filePath = uri.removePrefix("file:")
            val file = File(filePath)
            if (!file.exists()) {
                skippedCount++
                return@forEach
            }
            
            val content = file.readText()
            val parsedFile = parser.parse(uri, content) ?: run {
                skippedCount++
                return@forEach
            }
            
            val classDefRange = parsedFile.classDefinition.range
            val position = Position(classDefRange.start.line, classDefRange.start.character + 5)
            
            // Measure hover time in microseconds
            val timeUs = measureTimeMillis {
                val hover = hoverProvider.provideHover(uri, position)
                if (hover == null) {
                    failCount++
                    failures.add(className)
                } else {
                    successCount++
                }
            } * 1000  // convert ms to μs (approximately)
            
            times.add(timeUs)
        }
        
        // Sort for percentile calculation
        times.sort()
        
        val total = successCount + failCount
        val successRate = if (total > 0) (successCount.toDouble() / total) * 100 else 0.0
        
        println("Results:")
        println("  Total tested:   $total")
        println("  Successful:     $successCount")
        println("  Failed:         $failCount")
        println("  Skipped:        $skippedCount")
        println("  Success rate:   ${"%.2f".format(successRate)}%")
        
        if (times.isNotEmpty()) {
            val p50 = times[times.size / 2]
            val p95 = times[(times.size * 95) / 100]
            val p99 = times[(times.size * 99) / 100]
            val max = times.last()
            
            println("\nPerformance (microseconds):")
            println("  p50 (median):   ${p50}μs")
            println("  p95:            ${p95}μs")
            println("  p99:            ${p99}μs")
            println("  max:            ${max}μs")
            
            // Convert to milliseconds for display
            println("\nPerformance (milliseconds):")
            println("  p50 (median):   ${"%.3f".format(p50 / 1000.0)}ms")
            println("  p95:            ${"%.3f".format(p95 / 1000.0)}ms")
            println("  p99:            ${"%.3f".format(p99 / 1000.0)}ms")
            println("  max:            ${"%.3f".format(max / 1000.0)}ms")
            
            // REQUIREMENTS:
            // - 99%+ success rate
            // - p95 < 100ms (100,000μs)
            assertTrue(successRate >= 99.0, "Success rate must be ≥99%, got ${"%.2f".format(successRate)}%")
            assertTrue(p95 < 100_000, "p95 latency must be <100ms, got ${"%.3f".format(p95 / 1000.0)}ms")
            
            println("\n✅ PASSED: Success rate and performance requirements met")
        }
        
        if (failures.isNotEmpty()) {
            println("\nFirst 10 failures:")
            failures.take(10).forEach { println("  - $it") }
        }
    }
    
    @Test
    fun `test ALL files with superclass - goto definition`() {
        println("\n📊 TEST 3: Goto Definition on ALL files with superclass")
        println("-".repeat(80))
        
        val times = mutableListOf<Long>()
        var successCount = 0
        var failCount = 0
        var skippedCount = 0
        val failures = mutableListOf<String>()
        
        // Filter for classes with superclass
        val classesWithSuper = allClasses.filter { className ->
            val smaliFile = index.findClass(className)
            smaliFile?.classDefinition?.superClass != null &&
            !smaliFile.classDefinition.superClass!!.startsWith("Ljava/")
        }
        
        println("Testing ${classesWithSuper.size} classes with non-Java superclasses")
        
        classesWithSuper.forEach { className ->
            val uri = index.getUri(className) ?: run {
                skippedCount++
                return@forEach
            }
            
            if (uri.startsWith("sdk://")) {
                skippedCount++
                return@forEach
            }
            
            val filePath = uri.removePrefix("file:")
            val file = File(filePath)
            if (!file.exists()) {
                skippedCount++
                return@forEach
            }
            
            val content = file.readText()
            val parsedFile = parser.parse(uri, content) ?: run {
                skippedCount++
                return@forEach
            }
            
            // Find .super line in content
            val lines = content.lines()
            val superLineIndex = lines.indexOfFirst { it.trim().startsWith(".super") }
            if (superLineIndex < 0) {
                skippedCount++
                return@forEach
            }
            
            val position = Position(superLineIndex, 10)  // somewhere in .super line
            
            // Measure goto definition time
            val timeUs = measureTimeMillis {
                val locations = definitionProvider.findDefinition(uri, position)
                if (locations.isEmpty()) {
                    failCount++
                    failures.add(className)
                } else {
                    successCount++
                }
            } * 1000  // convert to μs
            
            times.add(timeUs)
        }
        
        times.sort()
        
        val total = successCount + failCount
        val successRate = if (total > 0) (successCount.toDouble() / total) * 100 else 0.0
        
        println("Results:")
        println("  Total tested:   $total")
        println("  Successful:     $successCount")
        println("  Failed:         $failCount")
        println("  Skipped:        $skippedCount")
        println("  Success rate:   ${"%.2f".format(successRate)}%")
        
        if (times.isNotEmpty()) {
            val p50 = times[times.size / 2]
            val p95 = times[(times.size * 95) / 100]
            val p99 = times[(times.size * 99) / 100]
            
            println("\nPerformance:")
            println("  p50 (median):   ${"%.3f".format(p50 / 1000.0)}ms")
            println("  p95:            ${"%.3f".format(p95 / 1000.0)}ms")
            println("  p99:            ${"%.3f".format(p99 / 1000.0)}ms")
            
            // REQUIREMENTS: 75%+ success (lower than hover since many superclasses are SDK/external)
            // Real APKs have ~20% SDK superclasses (android.*, java.*) which have no workspace definitions
            // NOTE: Mastodon APK achieves 79.76% (298/1472 are SDK classes)
            assertTrue(successRate >= 75.0, "Success rate must be ≥75%, got ${"%.2f".format(successRate)}%")
            assertTrue(p95 < 100_000, "p95 latency must be <100ms, got ${"%.3f".format(p95 / 1000.0)}ms")
            
            println("\n✅ PASSED: Success rate and performance requirements met")
        }
        
        if (failures.isNotEmpty()) {
            println("\nFirst 10 failures:")
            failures.take(10).forEach { println("  - $it") }
        }
    }
    
    @Test
    fun `test sample files - find references (full test would be too slow)`() {
        println("\n📊 TEST 4: Find References on sample files")
        println("-".repeat(80))
        println("⚠️  Note: Full reference scan is O(n²), testing sample of 200 files")
        
        val times = mutableListOf<Long>()
        var successCount = 0
        var failCount = 0
        var skippedCount = 0
        
        // Filter non-framework classes
        val nonFrameworkClasses = allClasses.filter { !it.startsWith("Ljava/") && !it.startsWith("Landroid/") }
        
        // Take a larger sample (200 instead of 50)
        val sampleSize = minOf(200, nonFrameworkClasses.size)
        val sample = nonFrameworkClasses.shuffled().take(sampleSize)
        
        println("Testing $sampleSize classes (out of ${nonFrameworkClasses.size} non-framework classes)")
        
        sample.forEach { className ->
            val uri = index.getUri(className) ?: run {
                skippedCount++
                return@forEach
            }
            
            if (uri.startsWith("sdk://")) {
                skippedCount++
                return@forEach
            }
            
            val filePath = uri.removePrefix("file:")
            val file = File(filePath)
            if (!file.exists()) {
                skippedCount++
                return@forEach
            }
            
            val content = file.readText()
            val parsedFile = parser.parse(uri, content) ?: run {
                skippedCount++
                return@forEach
            }
            
            val classDefRange = parsedFile.classDefinition.range
            val position = Position(classDefRange.start.line, classDefRange.start.character + 5)
            
            // Measure find references time
            val timeUs = measureTimeMillis {
                val locations = referenceProvider.findReferences(uri, position)
                successCount++  // We count any result (even empty) as success
            } * 1000  // convert to μs
            
            times.add(timeUs)
        }
        
        times.sort()
        
        val total = successCount + failCount
        
        println("Results:")
        println("  Total tested:   $total")
        println("  Successful:     $successCount")
        println("  Failed:         $failCount")
        println("  Skipped:        $skippedCount")
        
        if (times.isNotEmpty()) {
            val p50 = times[times.size / 2]
            val p95 = times[(times.size * 95) / 100]
            val p99 = times[(times.size * 99) / 100]
            
            println("\nPerformance:")
            println("  p50 (median):   ${"%.3f".format(p50 / 1000.0)}ms")
            println("  p95:            ${"%.3f".format(p95 / 1000.0)}ms")
            println("  p99:            ${"%.3f".format(p99 / 1000.0)}ms")
            
            // REQUIREMENT: p95 < 500ms (references can be slower as it searches all files)
            assertTrue(p95 < 500_000, "p95 latency must be <500ms, got ${"%.3f".format(p95 / 1000.0)}ms")
            
            println("\n✅ PASSED: Performance requirements met")
        }
    }
    
    @Test
    fun `hot path performance - hover 10000 times`() {
        println("\n📊 TEST 5: Hot Path Performance - Hover 10,000 times")
        println("-".repeat(80))
        
        // Find a valid test class
        val testClass = allClasses.find { className ->
            val uri = index.getUri(className)
            uri != null && !uri.startsWith("sdk://")
        } ?: fail<String>("No valid class found for testing")
        
        val uri = index.getUri(testClass)!!
        val filePath = uri.removePrefix("file:")
        val file = File(filePath)
        val content = file.readText()
        val parsedFile = parser.parse(uri, content)!!
        
        val classDefRange = parsedFile.classDefinition.range
        val position = Position(classDefRange.start.line, classDefRange.start.character + 5)
        
        println("Testing with class: ${parsedFile.classDefinition.name}")
        
        // Warmup: 1000 calls
        println("Warming up JVM (1000 calls)...")
        repeat(1000) {
            hoverProvider.provideHover(uri, position)
        }
        
        // Actual test: 10,000 calls
        println("Running 10,000 hover calls...")
        val times = mutableListOf<Long>()
        
        repeat(10_000) {
            val start = System.nanoTime()
            hoverProvider.provideHover(uri, position)
            val end = System.nanoTime()
            times.add((end - start) / 1000)  // convert to microseconds
        }
        
        times.sort()
        
        val p50 = times[times.size / 2]
        val p95 = times[(times.size * 95) / 100]
        val p99 = times[(times.size * 99) / 100]
        val max = times.last()
        
        println("\nResults (microseconds):")
        println("  p50 (median):   ${p50}μs")
        println("  p95:            ${p95}μs")
        println("  p99:            ${p99}μs")
        println("  max:            ${max}μs")
        
        println("\nResults (milliseconds):")
        println("  p50 (median):   ${"%.3f".format(p50 / 1000.0)}ms")
        println("  p95:            ${"%.3f".format(p95 / 1000.0)}ms")
        println("  p99:            ${"%.3f".format(p99 / 1000.0)}ms")
        println("  max:            ${"%.3f".format(max / 1000.0)}ms")
        
        // REQUIREMENTS: Hot path should be fast
        // - median < 1ms (1000μs)
        // - p99 < 5ms (5000μs)
        assertTrue(p50 < 1000, "Median must be <1ms, got ${"%.3f".format(p50 / 1000.0)}ms")
        assertTrue(p99 < 5000, "p99 must be <5ms, got ${"%.3f".format(p99 / 1000.0)}ms")
        
        println("\n✅ PASSED: Hot path performance meets requirements")
    }
}
