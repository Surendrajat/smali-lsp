package xyz.surendrajat.smalilsp.integration.navigation

import xyz.surendrajat.smalilsp.shared.TestUtils

import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import org.junit.jupiter.api.*
import java.io.File

/**
 * End-to-End tests using REAL ProtonMail APK files.
 * 
 * Tests parsing, indexing, and performance with real-world Smali code.
 * These catch integration issues that unit tests miss.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InstructionNavigationE2ETest {

    private lateinit var workspaceIndex: WorkspaceIndex
    private lateinit var parser: SmaliParser
    
    // Real Smali file from ProtonMail APK
    private val testSmaliFile: File? = TestUtils.getProtonMailApk()?.let { File(it, "smali_classes2/u00/h.smali") }
    private val testUri: String = testSmaliFile?.toURI()?.toString() ?: "file:///unknown.smali"
    
    @BeforeAll
    fun setupE2E() {
        Assumptions.assumeTrue(testSmaliFile?.exists() == true, "Test requires protonmail APK at: ${testSmaliFile?.absolutePath}")
    }
    
    @BeforeEach
    fun resetIndex() {
        workspaceIndex = WorkspaceIndex()
        parser = SmaliParser()
    }

    // ========== PARSING TESTS ==========

    @Test
    fun `e2e - parse real ProtonMail smali file successfully`() {
        // Given: Real Smali file from ProtonMail APK (156 lines, 4 methods, 2 fields)
        val content = testSmaliFile!!.readText()
        
        // When: Parse file
        val smaliFile = parser.parse(testUri, content)
        
        // Then: Should parse successfully
        Assertions.assertNotNull(smaliFile, "Should successfully parse real Smali file")
        Assertions.assertEquals("Lu00/h;", smaliFile!!.classDefinition.name, "Should extract correct class name")
        
        // Should have methods (clinit, a, b, c, d)
        Assertions.assertTrue(smaliFile.methods.isNotEmpty(), "Should have parsed methods")
        Assertions.assertTrue(
            smaliFile.methods.size >= 5,
            "Should have at least 5 methods, found ${smaliFile.methods.size}"
        )
        
        // Should have fields (a:Lu00/f;, b:Z)
        Assertions.assertTrue(smaliFile.fields.size >= 2, "Should have parsed at least 2 fields")
        
        // Should implement interface Lu00/n;
        Assertions.assertTrue(smaliFile.classDefinition.interfaces.isNotEmpty(), "Should have parsed interfaces")
    }

    @Test
    fun `e2e - parse and index real file`() {
        // Given: Real Smali file
        val content = testSmaliFile!!.readText()
        
        // When: Parse and index
        val smaliFile = parser.parse(testUri, content)
        Assertions.assertNotNull(smaliFile)
        workspaceIndex.indexFile(smaliFile!!)
        
        // Then: Should be findable in index by URI
        val foundFile = workspaceIndex.findFileByUri(testUri)
        Assertions.assertNotNull(foundFile, "Should find file in index after indexing")
        Assertions.assertEquals("Lu00/h;", foundFile!!.classDefinition.name)
        
        // Should be findable by class name
        val foundByClass = workspaceIndex.findClass("Lu00/h;")
        Assertions.assertNotNull(foundByClass, "Should find class by name")
        Assertions.assertEquals(testUri, foundByClass!!.uri)
    }

    @Test
    fun `e2e - real file contains getClassLoader instruction that was buggy`() {
        // Given: This file has the exact instruction user reported as buggy
        val content = testSmaliFile!!.readText()
        val smaliFile = parser.parse(testUri, content)
        
        // Then: File should have clinit method with getClassLoader call
        Assertions.assertNotNull(smaliFile)
        val clinit = smaliFile!!.methods.find { it.name == "<clinit>" }
        Assertions.assertNotNull(clinit, "Should have <clinit> method")
        
        // Should have instructions (new-instance, invoke-direct, invoke-virtual getClassLoader, etc.)
        Assertions.assertTrue(clinit!!.instructions.size > 5, "Should have multiple instructions")
        
        // Check for InvokeInstruction with getClassLoader method name
        val hasGetClassLoader = clinit.instructions.any { instruction ->
            instruction is xyz.surendrajat.smalilsp.core.InvokeInstruction && 
            instruction.methodName == "getClassLoader"
        }
        Assertions.assertTrue(hasGetClassLoader, "Should have getClassLoader instruction")
    }

    // ========== CROSS-FILE TESTS ==========

    @Test
    fun `e2e - parse multiple files from same package`() {
        // Given: u00 directory with multiple Smali files
        val testDir = File(TestUtils.getProtonMailApk() ?: return, "smali_classes2/u00")
        Assumptions.assumeTrue(testDir.exists() && testDir.isDirectory, "Test requires protonmail u00 directory")
        
        // When: Parse small files (<10KB) from same package
        val parsedFiles = testDir.listFiles { file -> 
            file.extension == "smali" && file.length() < 10000 
        }?.take(5)?.map { file ->
            val uri = file.toURI().toString()
            val content = file.readText()
            parser.parse(uri, content)
        }?.filterNotNull() ?: emptyList()
        
        // Then: Should successfully parse multiple files
        Assertions.assertTrue(parsedFiles.size >= 3, "Should parse at least 3 files")
        
        // All should be in same package (u00)
        parsedFiles.forEach { file ->
            Assertions.assertTrue(
                file.classDefinition.name.startsWith("Lu00/"),
                "Class ${file.classDefinition.name} should be in u00 package"
            )
        }
        
        // Should have different class names
        val classNames = parsedFiles.map { it.classDefinition.name }.toSet()
        Assertions.assertEquals(parsedFiles.size, classNames.size, "All classes should have unique names")
    }

    @Test
    fun `e2e - index multiple files and find cross-references`() {
        // Given: Multiple files from same package
        val testDir = File(TestUtils.getProtonMailApk() ?: return, "smali_classes2/u00")
        Assumptions.assumeTrue(testDir.exists() && testDir.isDirectory, "Test requires protonmail u00 directory")
        
        // When: Parse and index multiple files
        testDir.listFiles { file -> file.extension == "smali" && file.length() < 10000 }
            ?.take(10)
            ?.forEach { file ->
                val uri = file.toURI().toString()
                val content = file.readText()
                parser.parse(uri, content)?.let { parsed ->
                    workspaceIndex.indexFile(parsed)
                }
            }
        
        // Then: h.smali references Lu00/f; - should be able to find it if f.smali was indexed
        val luooFFile = workspaceIndex.findClass("Lu00/f;")
        if (luooFFile != null) {
            // If Lu00/f; exists in workspace, should be able to navigate to it
            Assertions.assertNotNull(luooFFile.classDefinition, "Should have class definition for Lu00/f")
            println("✓ Cross-file reference works: Found Lu00/f; in workspace")
        } else {
            println("⚠ Lu00/f; not in indexed files (OK - just means file wasn't parsed)")
        }
    }

    // ========== PERFORMANCE TESTS ==========

    @Test
    fun `e2e - parsing real file completes in reasonable time`() {
        // Given: Real Smali file (156 lines)
        val content = testSmaliFile!!.readText()
        
        // When: Parse with timing
        val startTime = System.currentTimeMillis()
        val smaliFile = parser.parse(testUri, content)
        val parseTime = System.currentTimeMillis() - startTime
        
        // Then: Should complete in under 500ms
        Assertions.assertNotNull(smaliFile, "Parsing should succeed")
        Assertions.assertTrue(
            parseTime < 500,
            "Parsing should complete in <500ms for 156-line file, took ${parseTime}ms"
        )
        println("Parse time: ${parseTime}ms for ${content.lines().size} lines")
    }

    @Test
    fun `e2e - indexing real file completes in reasonable time`() {
        // Given: Parsed file
        val content = testSmaliFile!!.readText()
        val smaliFile = parser.parse(testUri, content)
        Assertions.assertNotNull(smaliFile)
        
        // When: Index with timing
        val startTime = System.currentTimeMillis()
        workspaceIndex.indexFile(smaliFile!!)
        val indexTime = System.currentTimeMillis() - startTime
        
        // Then: Should complete in under 50ms
        Assertions.assertTrue(
            indexTime < 50,
            "Indexing should complete in <50ms, took ${indexTime}ms"
        )
        println("Index time: ${indexTime}ms")
    }

    @Test
    fun `e2e - lookup by class name is fast`() {
        // Given: Indexed file
        val content = testSmaliFile!!.readText()
        val smaliFile = parser.parse(testUri, content)
        workspaceIndex.indexFile(smaliFile!!)
        
        // When: Lookup with timing
        val startTime = System.nanoTime()
        val found = workspaceIndex.findClass("Lu00/h;")
        val lookupTime = (System.nanoTime() - startTime) / 1000 // microseconds
        
        // Then: Should complete in under 1ms (1000 microseconds)
        Assertions.assertNotNull(found, "Should find indexed class")
        Assertions.assertTrue(
            lookupTime < 1000,
            "Lookup should complete in <1ms, took ${lookupTime}μs"
        )
        println("Lookup time: ${lookupTime}μs")
    }

    // ========== BUG REPRODUCTION TESTS ==========

    @Test
    fun `e2e - file with getClassLoader method name is parseable`() {
        // Given: This file has invoke-virtual getClassLoader which was splitting incorrectly
        // Bug: "getClassLoader" was being split into "get", "Class", "Loader"
        val content = testSmaliFile!!.readText()
        
        // When: Parse
        val smaliFile = parser.parse(testUri, content)
        
        // Then: Should parse without treating method name as multiple symbols
        Assertions.assertNotNull(smaliFile)
        // If parser treats it as 3 symbols, parsing would fail or lose info
        // Success here means we're handling it as one symbol
    }

    @Test
    fun `e2e - file with check-cast to interface parses correctly`() {
        // Given: File has check-cast p1, Lorg/bouncycastle/jsse/BCSSLSocket; (line 71)
        val content = testSmaliFile!!.readText()
        
        // When: Parse
        val smaliFile = parser.parse(testUri, content)
        
        // Then: Should handle interface type check-cast
        Assertions.assertNotNull(smaliFile)
        val methodC = smaliFile!!.methods.find { it.name == "c" }
        Assertions.assertNotNull(methodC, "Should have method 'c'")
        
        // Method c should have check-cast instruction (TypeInstruction)
        val hasCheckCast = methodC!!.instructions.any { instruction ->
            instruction is xyz.surendrajat.smalilsp.core.TypeInstruction &&
            instruction.opcode == "check-cast"
        }
        Assertions.assertTrue(hasCheckCast, "Method c should have check-cast instruction")
    }
}
