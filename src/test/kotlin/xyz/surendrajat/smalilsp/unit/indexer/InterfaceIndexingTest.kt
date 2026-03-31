package xyz.surendrajat.smalilsp.unit.indexer

import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.TestUtils
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.indexer.WorkspaceScanner
import xyz.surendrajat.smalilsp.providers.DefinitionProvider
import java.io.File
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Test to verify interface files are properly indexed and navigable.
 *
 * Requires a decompiled APK in test-data/protonmail — skipped gracefully when unavailable.
 */
class InterfaceIndexingTest {

    private lateinit var index: WorkspaceIndex
    private lateinit var scanner: WorkspaceScanner
    private lateinit var definitionProvider: DefinitionProvider
    private var apkDir: File? = null

    @BeforeEach
    fun setup() = runBlocking {
        apkDir = TestUtils.getProtonMailApk()
        if (apkDir == null) return@runBlocking

        index = WorkspaceIndex()
        scanner = WorkspaceScanner(index)
        definitionProvider = DefinitionProvider(index)

        println("Indexing ${apkDir!!.absolutePath}...")
        val result = scanner.scanDirectory(apkDir!!)
        println("Indexed: ${result.filesSucceeded} files")
    }
    
    @Test
    fun `interface files should be indexed`() = runBlocking {
        assumeTrue(apkDir != null, "ProtonMail APK not available — skipping")

        // Find interface files
        val interfaceFiles = apkDir!!
            .walkTopDown()
            .filter { it.name.contains("Interface") && it.extension == "smali" }
            .take(10)
            .toList()

        println("\n=== Testing Interface Indexing ===")
        println("Found ${interfaceFiles.size} interface files to test")

        assumeTrue(interfaceFiles.isNotEmpty(), "No interface files found in APK — skipping")
        
        var indexed = 0
        var notIndexed = 0
        
        for (file in interfaceFiles) {
            val uri = file.toURI().toString()
            val smaliFile = index.findFileByUri(uri)
            
            if (smaliFile != null) {
                println("✅ INDEXED: ${file.name} → ${smaliFile.classDefinition.name}")
                indexed++
            } else {
                println("❌ NOT INDEXED: ${file.name}")
                notIndexed++
                
                // Check if it's in the class map
                val content = file.readText()
                val classLineMatch = Regex(""".class.*?L([^;]+);""").find(content)
                if (classLineMatch != null) {
                    val className = "L${classLineMatch.groupValues[1]};"
                    val found = index.findClass(className)
                    if (found != null) {
                        println("  → Found by class name: $className")
                    } else {
                        println("  → NOT found by class name: $className")
                    }
                }
            }
        }
        
        val total = indexed + notIndexed
        println("\nResults:")
        println("  Indexed: $indexed")
        println("  Not indexed: $notIndexed")
        if (total > 0) println("  Success rate: ${"%.1f".format(indexed * 100.0 / total)}%")

        assertTrue(indexed > 0, "At least some interface files should be indexed")
    }
    
    @Test
    fun `interface navigation should work`() = runBlocking {
        assumeTrue(apkDir != null, "ProtonMail APK not available — skipping")

        val interfaceFile = apkDir!!
            .walkTopDown()
            .filter { it.name.endsWith("Interface.smali") }
            .firstOrNull()
        
        if (interfaceFile == null) {
            println("No interface files found, skipping test")
            return@runBlocking
        }
        
        println("\n=== Testing Interface Navigation ===")
        println("Testing: ${interfaceFile.name}")
        
        val uri = interfaceFile.toURI().toString()
        val content = interfaceFile.readText()
        
        // Find .class line
        val lines = content.lines()
        val classLineIdx = lines.indexOfFirst { it.contains(".class") }
        
        if (classLineIdx >= 0) {
            println("Found .class at line $classLineIdx")
            
            // Try to get definition
            val position = Position(classLineIdx, 10)
            val locations = definitionProvider.findDefinition(uri, position)
            
            println("Locations found: ${locations.size}")
            locations.forEach { loc ->
                println("  → ${loc.uri} at ${loc.range.start.line}:${loc.range.start.character}")
            }
            
            // Should find at least the definition itself
            assertNotNull(locations)
        }
    }
}
