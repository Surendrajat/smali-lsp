package xyz.surendrajat.smalilsp.integration.workspace

import kotlinx.coroutines.runBlocking
import xyz.surendrajat.smalilsp.shared.TestUtils
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.indexer.WorkspaceScanner
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import xyz.surendrajat.smalilsp.providers.DocumentSymbolProvider
import xyz.surendrajat.smalilsp.providers.HoverProvider
import xyz.surendrajat.smalilsp.providers.ReferenceProvider
import org.eclipse.lsp4j.Position
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * Real-world integration test using actual decompiled APK files.
 * 
 * This test validates the entire LSP server stack with real Smali code from:
 * - Mastodon APK: 4,415 smali files (small real-world app)
 * 
 * Performance targets:
 * - Indexing: <30 seconds for 4,415 files
 * - Hover: <100ms average response time
 * - Goto definition: <50ms average response time
 * - Find references: <200ms average response time
 * - Document symbols: <50ms for typical file
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealWorldTest {

    private lateinit var mastodonPath: File
    
    private lateinit var index: WorkspaceIndex
    private lateinit var parser: SmaliParser
    private lateinit var hoverProvider: HoverProvider
    private lateinit var definitionProvider: DefinitionProvider
    private lateinit var referenceProvider: ReferenceProvider
    private lateinit var documentSymbolProvider: DocumentSymbolProvider
    
    private val indexedClasses = mutableListOf<String>()
    
    @BeforeAll
    fun setup() {
        println("=".repeat(80))
        println("REAL-WORLD TEST: Mastodon APK (4,415 files)")
        println("=".repeat(80))

        mastodonPath = TestUtils.requireMastodonApk()
        
        // Initialize components
        index = WorkspaceIndex()
        parser = SmaliParser()
        hoverProvider = HoverProvider(index)
        definitionProvider = DefinitionProvider(index)
        referenceProvider = ReferenceProvider(index)
        documentSymbolProvider = DocumentSymbolProvider()
        
        // Index the mastodon APK
        println("\n📂 Indexing mastodon APK...")
        val scanner = WorkspaceScanner(index)
        
        var lastReported = 0
        val result = runBlocking {
            scanner.scanDirectory(mastodonPath) { processed, total ->
                val percentDone = (processed * 100) / total
                if (percentDone >= lastReported + 10) {
                    println("   Progress: $processed/$total files ($percentDone%)")
                    lastReported = percentDone
                }
            }
        }
        
        println("\n✅ Indexing complete:")
        println("   Files processed: ${result.filesProcessed}")
        println("   Files succeeded: ${result.filesSucceeded}")
        println("   Files failed: ${result.filesFailed}")
        println("   Duration: ${result.durationMs}ms (${result.durationMs / 1000.0}s)")
        
        val indexTimeMs = result.durationMs
        
        // Collect all indexed classes by iterating through smali files and checking with index
        // We parse each file with our parser to get the actual class name  
        val smaliFiles = mastodonPath.walkTopDown()
            .filter { it.extension == "smali" }
            .toList()
        
        println("   Collecting class names from ${smaliFiles.size} files...")
        smaliFiles.forEach { file ->
            try {
                val uri = "file://${file.absolutePath}"
                val content = file.readText()
                val parsed = parser.parse(uri, content)
                if (parsed != null) {
                    indexedClasses.add(parsed.classDefinition.name)
                }
            } catch (e: Exception) {
                // Skip files that fail to parse
            }
        }
        
        println("   Successfully parsed classes available for testing: ${indexedClasses.size}")
        
        val stats = index.getStats()
        println("\n📊 Index statistics:")
        println("   Classes: ${stats.classes}")
        println("   Methods: ${stats.methods}")
        println("   Fields: ${stats.fields}")
        println("   Indexing time: ${indexTimeMs}ms (${indexTimeMs / 1000.0}s)")
        println("=".repeat(80))
        println()
    }
    
    @Test
    fun `mastodon APK indexes successfully`() {
        // Verify we got reasonable number of classes
        val stats = index.getStats()
        
        assertTrue(stats.classes > 1000, "Expected >1000 classes, got ${stats.classes}")
        assertTrue(stats.methods > 5000, "Expected >5000 methods, got ${stats.methods}")
        assertTrue(stats.fields > 1000, "Expected >1000 fields, got ${stats.fields}")
        
        println("✅ Index contains ${stats.classes} classes, ${stats.methods} methods, ${stats.fields} fields")
    }
    
    @Test
    fun `hover works on real classes`() {
        // Get a sample of classes to test hover on
        val sampleSize = minOf(50, indexedClasses.size)
        val sample = indexedClasses.shuffled().take(sampleSize)
        
        var totalTime = 0L
        var successCount = 0
        var skippedCount = 0
        
        sample.forEach { className ->
            val uri = index.getUri(className)
            if (uri == null) {
                skippedCount++
                return@forEach
            }
            if (uri.startsWith("sdk://")) {
                skippedCount++
                return@forEach
            }
            
            // Read the file - URI format is "file:/path" so remove "file:" prefix
            val filePath = uri.removePrefix("file:")
            val file = File(filePath)
            if (!file.exists()) {
                skippedCount++
                return@forEach
            }
            val content = file.readText()
            
            // Parse and find the class definition line
            val parsedFile = parser.parse(uri, content)
            if (parsedFile == null) {
                skippedCount++
                return@forEach
            }
            
            val classDefRange = parsedFile.classDefinition.range
            val position = Position(classDefRange.start.line, classDefRange.start.character + 5)
            
            // Test hover
            val hoverTime = measureTimeMillis {
                val hover = hoverProvider.provideHover(uri, position)
                if (hover != null) {
                    successCount++
                }
            }
            totalTime += hoverTime
        }
        
        val testedCount = sample.size - skippedCount
        val successRate = if (testedCount > 0) successCount * 100.0 / testedCount else 0.0
        val avgTime = if (successCount > 0) totalTime / successCount else 0
        println("✅ Hover: $successCount/$testedCount successful (skipped: $skippedCount), avg ${avgTime}ms")
        if (testedCount > 0) {
            assertTrue(successRate >= 95.0, "Hover should succeed for >=95% of tested classes, got ${"%.1f".format(successRate)}%")
            assertTrue(avgTime < 100, "Average hover time ${avgTime}ms exceeds 100ms target")
        }
    }
    
    @Test
    fun `goto definition works on real inheritance`() {
        // Find classes whose superclass is indexed in the workspace.
        val classesWithSuper = indexedClasses.mapNotNull { className ->
            val smaliFile = index.findClass(className)
            val superClass = smaliFile?.classDefinition?.superClass
            if (smaliFile != null && superClass != null && index.findClass(superClass) != null) {
                className
            } else {
                null
            }
        }.take(50)
        
        var totalTime = 0L
        var successCount = 0
        
        classesWithSuper.forEach { className ->
            val uri = index.getUri(className) ?: return@forEach
            if (uri.startsWith("sdk://")) return@forEach
            
            val file = File(uri.removePrefix("file:"))
            if (!file.exists()) return@forEach
            val content = file.readText()
            
            val parsedFile = parser.parse(uri, content) ?: return@forEach
            val superRange = parsedFile.classDefinition.superClassRange ?: return@forEach
            val position = Position(superRange.start.line, superRange.start.character + 1)
            
            // Test goto definition
            val gotoTime = measureTimeMillis {
                val locations = definitionProvider.findDefinition(uri, position)
                if (locations.isNotEmpty()) {
                    successCount++
                }
            }
            totalTime += gotoTime
        }
        
        val successRate = if (classesWithSuper.isNotEmpty()) successCount * 100.0 / classesWithSuper.size else 0.0
        val avgTime = if (classesWithSuper.isNotEmpty()) totalTime / classesWithSuper.size else 0
        println("✅ Goto definition: $successCount/${classesWithSuper.size} successful (${String.format("%.1f", successRate)}%), avg ${avgTime}ms")
        if (classesWithSuper.isNotEmpty()) {
            assertTrue(successRate >= 95.0, "Goto definition should resolve >=95% of workspace superclasses, got ${String.format("%.1f", successRate)}%")
            assertTrue(avgTime < 50, "Average goto definition time ${avgTime}ms exceeds 50ms target")
        }
    }
    
    @Test
    fun `find references works on real classes`() {
        // Only sample classes that have actual indexed references.
        val sampleClasses = indexedClasses.filter { className ->
            !className.startsWith("Ljava/") &&
            !className.startsWith("Landroid/") &&
            (index.findClassUsages(className).isNotEmpty() || index.findClassRefLocations(className).isNotEmpty())
        }
            .shuffled()
            .take(30)
        
        var totalTime = 0L
        var successCount = 0
        var testedCount = 0
        
        sampleClasses.forEach { className ->
            val uri = index.getUri(className) ?: return@forEach
            if (uri.startsWith("sdk://")) return@forEach
            
            val file = File(uri.removePrefix("file:"))
            if (!file.exists()) return@forEach
            val content = file.readText()
            
            val parsedFile = parser.parse(uri, content) ?: return@forEach
            val classDefRange = parsedFile.classDefinition.range
            val position = Position(classDefRange.start.line, classDefRange.start.character + 5)
            
            // Test find references
            val refTime = measureTimeMillis {
                val locations = referenceProvider.findReferences(uri, position, includeDeclaration = false)
                testedCount++
                if (locations.isNotEmpty()) {
                    successCount++
                }
            }
            totalTime += refTime
        }
        
        val successRate = if (testedCount > 0) successCount * 100.0 / testedCount else 0.0
        val avgTime = if (testedCount > 0) totalTime / testedCount else 0
        println("✅ Find references: $successCount/$testedCount successful (${String.format("%.1f", successRate)}%), avg ${avgTime}ms")
        if (testedCount > 0) {
            assertTrue(successRate >= 95.0, "Find references should resolve >=95% of sampled referenced classes, got ${String.format("%.1f", successRate)}%")
            assertTrue(avgTime < 200, "Average find references time ${avgTime}ms exceeds 200ms target")
        }
    }
    
    @Test
    fun `document symbols works on real files`() {
        // Get sample of files
        val sample = indexedClasses.shuffled().take(50)
        
        var totalTime = 0L
        var successCount = 0
        
        sample.forEach { className ->
            val uri = index.getUri(className) ?: return@forEach
            if (uri.startsWith("sdk://")) return@forEach
            
            val file = File(uri.removePrefix("file:"))
            if (!file.exists()) return@forEach
            val content = file.readText()
            
            // Test document symbols
            val symbolTime = measureTimeMillis {
                val parsedFile = parser.parse(uri, content)
                if (parsedFile != null) {
                    val symbols = documentSymbolProvider.provideDocumentSymbols(parsedFile)
                    if (symbols.isNotEmpty()) {
                        successCount++
                    }
                }
            }
            totalTime += symbolTime
        }
        
        val successRate = if (sample.isNotEmpty()) successCount * 100.0 / sample.size else 0.0
        val avgTime = if (sample.isNotEmpty()) totalTime / sample.size else 0
        println("✅ Document symbols: $successCount/${sample.size} successful (${String.format("%.1f", successRate)}%), avg ${avgTime}ms")
        if (sample.isNotEmpty()) {
            assertTrue(successRate >= 95.0, "Document symbols should succeed for >=95% of sampled files, got ${String.format("%.1f", successRate)}%")
            assertTrue(avgTime < 50, "Average document symbols time ${avgTime}ms exceeds 50ms target")
        }
    }
    
    @Test
    fun `index statistics are reasonable`() {
        val stats = index.getStats()
        
        // Mastodon has 4,415 files, so we expect similar number of classes
        assertTrue(stats.classes in 3000..6000, "Class count ${stats.classes} outside expected range")
        
        // Each class should have multiple methods on average
        val avgMethodsPerClass = stats.methods.toDouble() / stats.classes
        assertTrue(avgMethodsPerClass > 3.0, "Average methods per class $avgMethodsPerClass seems low")
        
        println("✅ Statistics: ${stats.classes} classes, ${stats.methods} methods, ${stats.fields} fields")
        println("   Average: ${String.format("%.1f", avgMethodsPerClass)} methods/class")
    }
    
    @Test
    fun `can resolve Android framework references`() {
        // Find files that reference Android framework classes OR Java framework classes
        var foundFrameworkRefs = 0
        var foundAndroidRefs = 0
        
        indexedClasses.take(100).forEach { className ->
            val smaliFile = index.findClass(className) ?: return@forEach
            
            // Check if it extends framework class
            val superClass = smaliFile.classDefinition.superClass
            if (superClass != null) {
                if (superClass.startsWith("Landroid/")) {
                    foundAndroidRefs++
                    foundFrameworkRefs++
                } else if (superClass.startsWith("Ljava/")) {
                    foundFrameworkRefs++
                }
            }
            
            // Check if it implements framework interface
            smaliFile.classDefinition.interfaces.forEach { iface ->
                if (iface.startsWith("Landroid/")) {
                    foundAndroidRefs++
                    foundFrameworkRefs++
                } else if (iface.startsWith("Ljava/")) {
                    foundFrameworkRefs++
                }
            }
        }
        
        println("✅ Found $foundFrameworkRefs framework references ($foundAndroidRefs Android) in first 100 classes")
        assertTrue(foundFrameworkRefs > 0, "Expected to find some framework references")
    }
    
    @Test
    fun `concurrent access works correctly`() {
        // Simulate multiple clients accessing the index concurrently
        val threads = (1..10).map { threadNum ->
            Thread {
                repeat(10) { iteration ->
                    // Random operations
                    if (indexedClasses.isNotEmpty()) {
                        val randomClass = indexedClasses.random()
                        index.findClass(randomClass)
                        index.getUri(randomClass)
                        index.hasClass(randomClass)
                    }
                }
            }
        }
        
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        
        // Verify index is still consistent
        val stats = index.getStats()
        assertTrue(stats.classes > 0)
        
        println("✅ Concurrent access test passed with ${stats.classes} classes")
    }
}
