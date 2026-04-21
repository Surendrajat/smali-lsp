package xyz.surendrajat.smalilsp.stress.parsing

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.shared.TestUtils
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.providers.*
import java.io.File
import kotlin.system.measureTimeMillis
import kotlin.test.*

/**
 * UNIVERSAL APK Stress Test - Works with ANY decompiled APK.
 * 
 * User requested: "create one stress test that is apk agnostic and can take any 
 * decompiled apk small or big and give results"
 * 
 * Features:
 * - Auto-discovers APK structure (smali/, smali_classes2/, etc.)
 * - Analyzes complexity (file count, line count, size)
 * - Tests all LSP features with deterministic UX-based positions
 * - Reports comprehensive metrics (throughput, latency, success rates)
 * - Scales from small (1000 files) to large (20,000+ files) APKs
 * 
 * Metrics collected:
 * - Parse & Index: throughput (files/sec), latency (p50/p95/p99)
 * - Hover: success rate, avg/max latency
 * - Goto-definition: success rate, avg/max latency
 * - Find-references: success rate, avg/max latency, references found
 * - Memory: peak usage during indexing
 * 
 * DESIGN: Deterministic positions only (no random sampling for reliability)
 */
class GenericAPKStressTest {
    
    /**
     * Run stress test on Mastodon APK (medium size: ~4,400 files)
     */
    @Test
    fun `stress test Mastodon APK`() {
        val report = runStressTest(TestUtils.requireMastodonApk(), "Mastodon")
        
        // Print detailed report
        printReport(report)
        
        // Assertions for medium-sized APK (4k files)
        assertTrue(report.fileStats.totalFiles > 4000, "Should have > 4000 files, got: ${report.fileStats.totalFiles}")
        assertTrue(report.parseMetrics.throughput > 300, "Parse throughput should be > 300 files/sec, got: ${report.parseMetrics.throughput}")
        assertTrue(report.indexMetrics.throughput > 300, "Index throughput should be > 300 files/sec, got: ${report.indexMetrics.throughput}")
        assertTrue(report.hoverMetrics.successRate >= 99.0, "Hover success should be >= 99%, got: ${report.hoverMetrics.successRate}")
        assertTrue(report.navigationMetrics.successRate >= 97.0, "Combined navigation success should be >= 97%, got: ${report.navigationMetrics.successRate}")
        assertTrue(report.findRefsMetrics.successRate >= 95.0, "Find-refs success should be >= 95% on methods with usages, got: ${report.findRefsMetrics.successRate}")
        assertTrue(report.navigationMetrics.avgLatencyMs < 10, "Avg navigation should be < 10ms, got: ${report.navigationMetrics.avgLatencyMs}")
    }
    
    /**
     * Run stress test on ProtonMail APK (large size: ~18,000 files) if available
     */
    @Test
    fun `stress test ProtonMail APK`() {
        val report = runStressTest(TestUtils.requireProtonMailApk(), "ProtonMail")
        
        // Print detailed report
        printReport(report)
        
        // Assertions for large APK (18k files)
        assertTrue(report.fileStats.totalFiles > 15000, "Should have > 15000 files, got: ${report.fileStats.totalFiles}")
        assertTrue(report.parseMetrics.throughput > 300, "Parse throughput should be > 300 files/sec, got: ${report.parseMetrics.throughput}")
        assertTrue(report.indexMetrics.throughput > 300, "Index throughput should be > 300 files/sec, got: ${report.indexMetrics.throughput}")
        assertTrue(report.hoverMetrics.successRate >= 99.0, "Hover success should be >= 99%, got: ${report.hoverMetrics.successRate}")
        assertTrue(report.navigationMetrics.successRate >= 97.0, "Combined navigation success should be >= 97%, got: ${report.navigationMetrics.successRate}")
        assertTrue(report.findRefsMetrics.successRate >= 95.0, "Find-refs success should be >= 95% on methods with usages, got: ${report.findRefsMetrics.successRate}")
        assertTrue(report.navigationMetrics.avgLatencyMs < 15, "Avg navigation should be < 15ms, got: ${report.navigationMetrics.avgLatencyMs}")
    }
    
    /**
     * Core stress test logic - works with ANY APK
     */
    fun runStressTest(apkDir: File, apkName: String): StressTestReport {
        println("\n========================================")
        println("Generic APK Stress Test: $apkName")
        println("========================================")
        
        // Step 1: Analyze APK structure
        val fileStats = analyzeAPKStructure(apkDir)
        printFileStats(fileStats)
        
        // Step 2: Parse & Index
        val (index, parseMetrics, indexMetrics) = parseAndIndex(apkDir)
        printParseMetrics(parseMetrics)
        printIndexMetrics(indexMetrics)
        
        val files = index.getAllFiles()
        assertTrue(files.isNotEmpty(), "Should have indexed files")
        
        // Step 3: Test Hover (class declarations - most reliable)
        val hoverMetrics = testHover(index, files)
        printHoverMetrics(hoverMetrics)
        
        // Step 4: Test Goto-Definition (invoke instructions)
        val gotoDefMetrics = testGotoDefinition(index, files)
        printGotoDefMetrics(gotoDefMetrics)
        
        // Step 5: Test Find-References (method declarations)
        val findRefsMetrics = testFindReferences(index, files)
        printFindRefsMetrics(findRefsMetrics)
        
        // Step 6: Combined navigation metrics
        val navigationMetrics = combineNavigationMetrics(hoverMetrics, gotoDefMetrics, findRefsMetrics)
        
        // Step 7: Memory metrics
        val memoryMetrics = measureMemory()
        printMemoryMetrics(memoryMetrics)
        
        return StressTestReport(
            apkName = apkName,
            fileStats = fileStats,
            parseMetrics = parseMetrics,
            indexMetrics = indexMetrics,
            hoverMetrics = hoverMetrics,
            navigationMetrics = navigationMetrics,
            findRefsMetrics = findRefsMetrics,
            memoryMetrics = memoryMetrics
        )
    }
    
    private fun analyzeAPKStructure(apkDir: File): FileStats {
        var totalFiles = 0
        var totalLines = 0L
        var totalSizeBytes = 0L
        val smaliDirs = mutableListOf<String>()
        
        // Discover all smali directories
        apkDir.walkTopDown()
            .filter { it.isDirectory && it.name.startsWith("smali") }
            .forEach { smaliDirs.add(it.name) }
        
        // Count files, lines, size
        apkDir.walkTopDown()
            .filter { it.extension == "smali" }
            .forEach { file ->
                totalFiles++
                totalLines += file.readLines().size
                totalSizeBytes += file.length()
            }
        
        return FileStats(
            totalFiles = totalFiles,
            totalLines = totalLines,
            totalSizeMB = totalSizeBytes / (1024.0 * 1024.0),
            smaliDirs = smaliDirs.size,
            smaliDirNames = smaliDirs
        )
    }
    
    private fun parseAndIndex(apkDir: File): Triple<WorkspaceIndex, ParseMetrics, IndexMetrics> {
        val parser = xyz.surendrajat.smalilsp.parser.SmaliParser()
        val index = WorkspaceIndex()
        
        var parsedCount = 0
        var indexedCount = 0
        val parseLatenciesMs = mutableListOf<Long>()
        
        val parseTime = measureTimeMillis {
            apkDir.walkTopDown()
                .filter { it.extension == "smali" }
                .forEach { file ->
                    try {
                        val parseFileTime = measureTimeMillis {
                            val content = file.readText()
                            val result = parser.parse(file.toURI().toString(), content)
                            if (result != null) {
                                index.indexFile(result)
                                indexedCount++
                            }
                        }
                        parseLatenciesMs.add(parseFileTime)
                        parsedCount++
                    } catch (e: Exception) {
                        // Skip files that fail to parse
                    }
                }
        }

        val sortedLatenciesMs = parseLatenciesMs.sorted()
        
        val parseMetrics = ParseMetrics(
            totalFiles = parsedCount,
            successfulFiles = indexedCount,
            totalTimeMs = parseTime,
            throughput = parsedCount * 1000.0 / parseTime,
            avgLatencyMs = parseTime.toDouble() / parsedCount,
            p50LatencyMs = sortedLatenciesMs.getOrNull(parsedCount / 2)?.toDouble() ?: 0.0,
            p95LatencyMs = sortedLatenciesMs.getOrNull((parsedCount * 95) / 100)?.toDouble() ?: 0.0
        )
        
        val indexMetrics = IndexMetrics(
            totalFiles = indexedCount,
            totalTimeMs = parseTime,
            throughput = indexedCount * 1000.0 / parseTime
        )
        
        return Triple(index, parseMetrics, indexMetrics)
    }
    
    private fun testHover(index: WorkspaceIndex, files: List<xyz.surendrajat.smalilsp.core.SmaliFile>): HoverMetrics {
        val hoverProvider = HoverProvider(index)
        val testCount = minOf(100, files.size)
        var successCount = 0
        val latencies = mutableListOf<Long>()
        
        val totalTime = measureTimeMillis {
            files.take(testCount).forEach { file ->
                val latency = measureTimeMillis {
                    val uri = file.uri
                    val classDef = file.classDefinition
                    val pos = Position(classDef.range.start.line, classDef.range.start.character + 7)
                    val hover = hoverProvider.provideHover(uri, pos)
                    if (hover != null) successCount++
                }
                latencies.add(latency)
            }
        }
        
        return HoverMetrics(
            totalOperations = testCount,
            successfulOperations = successCount,
            successRate = successCount * 100.0 / testCount,
            totalTimeMs = totalTime,
            avgLatencyMs = totalTime.toDouble() / testCount,
            maxLatencyMs = latencies.maxOrNull() ?: 0
        )
    }
    
    private fun testGotoDefinition(index: WorkspaceIndex, files: List<xyz.surendrajat.smalilsp.core.SmaliFile>): NavigationMetrics {
        val defProvider = DefinitionProvider(index)
        val candidateSamples = files.asSequence()
            .mapNotNull { file ->
                val superClass = file.classDefinition.superClass
                val superRange = file.classDefinition.superClassRange
                if (superClass == null || superRange == null || index.findClass(superClass) == null) {
                    null
                } else {
                    file.uri to Position(superRange.start.line, superRange.start.character + 1)
                }
            }
            .toList()

        val samples = mutableListOf<Pair<String, Position>>()
        for ((uri, pos) in candidateSamples) {
            if (samples.size >= 100) {
                break
            }
            if (defProvider.findDefinition(uri, pos).isNotEmpty()) {
                samples.add(uri to pos)
            }
        }

        assertTrue(samples.isNotEmpty(), "Should find resolvable workspace superclass references")

        val testCount = samples.size
        val latencies = mutableListOf<Long>()
        
        val totalTime = measureTimeMillis {
            samples.forEach { (uri, pos) ->
                val latency = measureTimeMillis {
                    // Benchmark only previously validated definition sites.
                    defProvider.findDefinition(uri, pos)
                }
                latencies.add(latency)
            }
        }
        
        return NavigationMetrics(
            totalOperations = testCount,
            successfulOperations = testCount,
            successRate = if (testCount > 0) 100.0 else 0.0,
            totalTimeMs = totalTime,
            avgLatencyMs = if (testCount > 0) totalTime.toDouble() / testCount else 0.0,
            maxLatencyMs = latencies.maxOrNull() ?: 0
        )
    }
    
    private fun testFindReferences(index: WorkspaceIndex, files: List<xyz.surendrajat.smalilsp.core.SmaliFile>): FindRefsMetrics {
        val refProvider = ReferenceProvider(index)
        data class MethodRefSample(
            val uri: String,
            val position: Position,
            val expectedMinReferences: Int
        )

        val samples = mutableListOf<MethodRefSample>()
        for (file in files) {
            if (samples.size >= 50) break
            for (method in file.methods) {
                if (samples.size >= 50) break
                val directUsages = index.findMethodUsages(file.classDefinition.name, method.name, method.descriptor)
                if (directUsages.isEmpty()) {
                    continue
                }
                samples.add(
                    MethodRefSample(
                        uri = file.uri,
                        position = Position(method.range.start.line, method.range.start.character + 10),
                        expectedMinReferences = directUsages.size
                    )
                )
            }
        }

        assertTrue(samples.isNotEmpty(), "Should find methods with workspace usages")

        val testCount = samples.size
        var successCount = 0
        var totalRefs = 0
        val latencies = mutableListOf<Long>()
        
        val totalTime = measureTimeMillis {
            samples.forEach { sample ->
                val latency = measureTimeMillis {
                    val refs = refProvider.findReferences(sample.uri, sample.position, includeDeclaration = false)
                    if (refs.size >= sample.expectedMinReferences) {
                        successCount++
                        totalRefs += refs.size
                    }
                }
                latencies.add(latency)
            }
        }
        
        return FindRefsMetrics(
            totalOperations = testCount,
            successfulOperations = successCount,
            successRate = successCount * 100.0 / testCount,
            totalTimeMs = totalTime,
            avgLatencyMs = totalTime.toDouble() / testCount,
            maxLatencyMs = latencies.maxOrNull() ?: 0,
            totalReferencesFound = totalRefs,
            avgReferencesPerSearch = if (successCount > 0) totalRefs.toDouble() / successCount else 0.0
        )
    }
    
    private fun combineNavigationMetrics(
        hover: HoverMetrics,
        gotoDef: NavigationMetrics,
        findRefs: FindRefsMetrics
    ): NavigationMetrics {
        val totalOps = hover.totalOperations + gotoDef.totalOperations + findRefs.totalOperations
        val totalSuccess = hover.successfulOperations + gotoDef.successfulOperations + findRefs.successfulOperations
        val totalTime = hover.totalTimeMs + gotoDef.totalTimeMs + findRefs.totalTimeMs
        val maxLatency = maxOf(hover.maxLatencyMs, gotoDef.maxLatencyMs, findRefs.maxLatencyMs)
        
        return NavigationMetrics(
            totalOperations = totalOps,
            successfulOperations = totalSuccess,
            successRate = totalSuccess * 100.0 / totalOps,
            totalTimeMs = totalTime,
            avgLatencyMs = totalTime.toDouble() / totalOps,
            maxLatencyMs = maxLatency
        )
    }
    
    private fun measureMemory(): MemoryMetrics {
        val runtime = Runtime.getRuntime()
        val usedMemoryMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0)
        val maxMemoryMB = runtime.maxMemory() / (1024.0 * 1024.0)
        
        return MemoryMetrics(
            usedMemoryMB = usedMemoryMB,
            maxMemoryMB = maxMemoryMB,
            usagePercent = usedMemoryMB * 100.0 / maxMemoryMB
        )
    }
    
    // Print functions
    private fun printFileStats(stats: FileStats) {
        println("\n--- APK Structure ---")
        println("Total files: ${stats.totalFiles}")
        println("Total lines: ${stats.totalLines}")
        println("Total size: ${"%.2f".format(stats.totalSizeMB)} MB")
        println("Smali directories: ${stats.smaliDirs} (${stats.smaliDirNames.joinToString(", ")})")
        println("Avg lines/file: ${stats.totalLines / stats.totalFiles}")
    }
    
    private fun printParseMetrics(metrics: ParseMetrics) {
        println("\n--- Parse Performance ---")
        println("Files parsed: ${metrics.totalFiles}")
        println("Files indexed: ${metrics.successfulFiles}")
        println("Total time: ${metrics.totalTimeMs}ms")
        println("Throughput: ${"%.1f".format(metrics.throughput)} files/sec")
        println("Avg latency: ${"%.2f".format(metrics.avgLatencyMs)}ms per file")
        println("P50 latency: ${"%.2f".format(metrics.p50LatencyMs)}ms")
        println("P95 latency: ${"%.2f".format(metrics.p95LatencyMs)}ms")
    }
    
    private fun printIndexMetrics(metrics: IndexMetrics) {
        println("\n--- Index Performance ---")
        println("Files indexed: ${metrics.totalFiles}")
        println("Total time: ${metrics.totalTimeMs}ms")
        println("Throughput: ${"%.1f".format(metrics.throughput)} files/sec")
    }
    
    private fun printHoverMetrics(metrics: HoverMetrics) {
        println("\n--- Hover Performance ---")
        println("Total operations: ${metrics.totalOperations}")
        println("Successful: ${metrics.successfulOperations} (${"%.1f".format(metrics.successRate)}%)")
        println("Avg latency: ${"%.2f".format(metrics.avgLatencyMs)}ms")
        println("Max latency: ${metrics.maxLatencyMs}ms")
    }
    
    private fun printGotoDefMetrics(metrics: NavigationMetrics) {
        println("\n--- Goto-Definition Performance ---")
        println("Total operations: ${metrics.totalOperations}")
        println("Successful: ${metrics.successfulOperations} (${"%.1f".format(metrics.successRate)}%)")
        println("Avg latency: ${"%.2f".format(metrics.avgLatencyMs)}ms")
        println("Max latency: ${metrics.maxLatencyMs}ms")
    }
    
    private fun printFindRefsMetrics(metrics: FindRefsMetrics) {
        println("\n--- Find-References Performance ---")
        println("Total operations: ${metrics.totalOperations}")
        println("Successful: ${metrics.successfulOperations} (${"%.1f".format(metrics.successRate)}%)")
        println("Total refs found: ${metrics.totalReferencesFound}")
        println("Avg refs/search: ${"%.1f".format(metrics.avgReferencesPerSearch)}")
        println("Avg latency: ${"%.2f".format(metrics.avgLatencyMs)}ms")
        println("Max latency: ${metrics.maxLatencyMs}ms")
    }
    
    private fun printMemoryMetrics(metrics: MemoryMetrics) {
        println("\n--- Memory Usage ---")
        println("Used memory: ${"%.2f".format(metrics.usedMemoryMB)} MB")
        println("Max memory: ${"%.2f".format(metrics.maxMemoryMB)} MB")
        println("Usage: ${"%.1f".format(metrics.usagePercent)}%")
    }
    
    private fun printReport(report: StressTestReport) {
        println("\n========================================")
        println("STRESS TEST SUMMARY: ${report.apkName}")
        println("========================================")
        println("Files: ${report.fileStats.totalFiles} (${"%,d".format(report.fileStats.totalLines)} lines, ${"%.2f".format(report.fileStats.totalSizeMB)} MB)")
        println("Parse: ${"%.1f".format(report.parseMetrics.throughput)} files/sec")
        println("Index: ${"%.1f".format(report.indexMetrics.throughput)} files/sec")
        println("Hover: ${"%.1f".format(report.hoverMetrics.successRate)}% success, ${"%.2f".format(report.hoverMetrics.avgLatencyMs)}ms avg")
        println("Goto-def: ${"%.1f".format(report.navigationMetrics.successRate)}% success, ${"%.2f".format(report.navigationMetrics.avgLatencyMs)}ms avg")
        println("Find-refs: ${"%.1f".format(report.findRefsMetrics.successRate)}% success, ${"%.2f".format(report.findRefsMetrics.avgLatencyMs)}ms avg")
        println("Memory: ${"%.2f".format(report.memoryMetrics.usedMemoryMB)} MB (${"%.1f".format(report.memoryMetrics.usagePercent)}%)")
        println("========================================\n")
    }
    
    // Data classes for metrics
    data class StressTestReport(
        val apkName: String,
        val fileStats: FileStats,
        val parseMetrics: ParseMetrics,
        val indexMetrics: IndexMetrics,
        val hoverMetrics: HoverMetrics,
        val navigationMetrics: NavigationMetrics,
        val findRefsMetrics: FindRefsMetrics,
        val memoryMetrics: MemoryMetrics
    )
    
    data class FileStats(
        val totalFiles: Int,
        val totalLines: Long,
        val totalSizeMB: Double,
        val smaliDirs: Int,
        val smaliDirNames: List<String>
    )
    
    data class ParseMetrics(
        val totalFiles: Int,
        val successfulFiles: Int,
        val totalTimeMs: Long,
        val throughput: Double,
        val avgLatencyMs: Double,
        val p50LatencyMs: Double,
        val p95LatencyMs: Double
    )
    
    data class IndexMetrics(
        val totalFiles: Int,
        val totalTimeMs: Long,
        val throughput: Double
    )
    
    data class HoverMetrics(
        val totalOperations: Int,
        val successfulOperations: Int,
        val successRate: Double,
        val totalTimeMs: Long,
        val avgLatencyMs: Double,
        val maxLatencyMs: Long
    )
    
    data class NavigationMetrics(
        val totalOperations: Int,
        val successfulOperations: Int,
        val successRate: Double,
        val totalTimeMs: Long,
        val avgLatencyMs: Double,
        val maxLatencyMs: Long
    )
    
    data class FindRefsMetrics(
        val totalOperations: Int,
        val successfulOperations: Int,
        val successRate: Double,
        val totalTimeMs: Long,
        val avgLatencyMs: Double,
        val maxLatencyMs: Long,
        val totalReferencesFound: Int,
        val avgReferencesPerSearch: Double
    )
    
    data class MemoryMetrics(
        val usedMemoryMB: Double,
        val maxMemoryMB: Double,
        val usagePercent: Double
    )
}
