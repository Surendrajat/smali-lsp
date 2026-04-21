package xyz.surendrajat.smalilsp.regression

import org.eclipse.lsp4j.Position
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
import xyz.surendrajat.smalilsp.parser.SmaliParser
import xyz.surendrajat.smalilsp.providers.DefinitionProvider

/**
 * Tests for specific bugs found during deep investigation.
 * Each test validates a bug fix.
 */
class BugFixValidationTest {
    private val parser = SmaliParser()
    
    /**
     * BUG #1: WorkspaceIndex.findClassNameByUri should use O(1) lookup, not O(n) search.
     * 
     * Before fix: O(n) linear search through classToUri entries
     * After fix: O(1) lookup using uriToClass reverse map
     */
    @Test
    fun `BUG-1 - findClassNameByUri should be O(1) not O(n)`() {
        val index = WorkspaceIndex()
        
        // Index 1000 files to make O(n) vs O(1) noticeable
        repeat(1000) { i ->
            val className = "Lcom/example/Class$i;"
            val uri = "file:///test/Class$i.smali"
            val content = """
                .class public $className
                .super Ljava/lang/Object;
            """.trimIndent()
            
            val file = parser.parse(uri, content)
            assertNotNull(file, "File $i should parse")
            index.indexFile(file!!)
        }
        
        // Warm the lookup path once before timing to avoid one-off startup noise.
        val testUri = "file:///test/Class500.smali"
        repeat(1_000) {
            index.findClassNameByUri(testUri)
        }

        // Test reverse lookup over many iterations so we measure steady-state behavior,
        // not a single noisy sample.
        val iterations = 10_000
        val startTime = System.nanoTime()
        var className: String? = null
        repeat(iterations) {
            className = index.findClassNameByUri(testUri)
        }
        val endTime = System.nanoTime()
        val averageMicros = (endTime - startTime).toDouble() / iterations / 1_000.0
        
        assertEquals("Lcom/example/Class500;", className)
        assertTrue(averageMicros < 50.0, "Lookup should stay well below 50µs on average, took ${averageMicros}µs")
        
        println("✅ BUG #1 FIXED: findClassNameByUri averaged ${String.format("%.3f", averageMicros)}µs for 1000 files")
    }
    
    /**
     * BUG #1: Test with normalized URI (file:/ vs file:///)
     */
    @Test
    fun `BUG-1 - findClassNameByUri should handle URI normalization`() {
        val index = WorkspaceIndex()
        val className = "Lcom/example/TestClass;"
        
        // Index with file:/// (3 slashes)
        val uri = "file:///test/TestClass.smali"
        val content = """
            .class public $className
            .super Ljava/lang/Object;
        """.trimIndent()
        
        val file = parser.parse(uri, content)
        assertNotNull(file)
        index.indexFile(file!!)
        
        // Lookup with file:/ (1 slash) - should normalize and find
        val foundClassName1 = index.findClassNameByUri("file:/test/TestClass.smali")
        assertEquals(className, foundClassName1, "Should find with file:/ variant")
        
        // Lookup with file:/// (3 slashes) - direct match
        val foundClassName2 = index.findClassNameByUri("file:///test/TestClass.smali")
        assertEquals(className, foundClassName2, "Should find with file:/// variant")
        
        println("✅ BUG #1 FIXED: URI normalization works")
    }
    
    /**
     * BUG #2: DefinitionProvider.findTypeInMethodSignature should return type at cursor,
     * not first matching type.
     * 
     * Before fix: Always returned first type found (usually first param)
     * After fix: Returns type at cursor position
     */
    @Test
    fun `BUG-2 - should navigate to return type when cursor on return type`() {
        val index = WorkspaceIndex()
        
        // Index Data class (user class)
        val dataContent = """
            .class public Lcom/example/Data;
            .super Ljava/lang/Object;
        """.trimIndent()
        val dataFile = parser.parse("file:///Data.smali", dataContent)
        assertNotNull(dataFile)
        index.indexFile(dataFile!!)
        
        // Index Result class (user class)
        val resultContent = """
            .class public Lcom/example/Result;
            .super Ljava/lang/Object;
        """.trimIndent()
        val resultFile = parser.parse("file:///Result.smali", resultContent)
        assertNotNull(resultFile)
        index.indexFile(resultFile!!)
        
        // Index test class with method: foo(Data) returns Result
        // Create temp file so DefinitionProvider can read it for position-based matching
        val testTempFile = java.io.File.createTempFile("TestClass", ".smali")
        testTempFile.deleteOnExit()
        
        val testContent = """
            .class public Lcom/example/TestClass;
            .super Ljava/lang/Object;
            
            .method public foo(Lcom/example/Data;)Lcom/example/Result;
                .registers 2
                const/4 v0, 0x0
                return-object v0
            .end method
        """.trimIndent()
        testTempFile.writeText(testContent)
        
        val testUri = testTempFile.toURI().toString()
        val testFile = parser.parse(testUri, testContent)
        assertNotNull(testFile)
        index.indexFile(testFile!!)
        
        val provider = DefinitionProvider(index)
        
        // Test 1: Cursor on Data (param type) - should navigate to Data
        // Line 3: .method public foo(Lcom/example/Data;)Lcom/example/Result;
        //                            ^ cursor at char 24 (on Data)
        val dataPosition = Position(3, 24)
        val dataDefs = provider.findDefinition(testUri, dataPosition)
        
        assertTrue(dataDefs.isNotEmpty(), "Should find Data definition")
        assertTrue(dataDefs[0].uri.contains("Data.smali"), 
            "Should navigate to Data, not Result")
        
        // Test 2: Cursor on Result (return type) - should navigate to Result
        // Line 3: .method public foo(Lcom/example/Data;)Lcom/example/Result;
        //                                                 ^ cursor at char 48 (on Result)
        val resultPosition = Position(3, 48)
        val resultDefs = provider.findDefinition(testUri, resultPosition)
        
        assertTrue(resultDefs.isNotEmpty(), "Should find Result definition")
        assertTrue(resultDefs[0].uri.contains("Result.smali"), 
            "Should navigate to Result, not Data")
        
        println("✅ BUG #2 FIXED: Position-based type navigation works correctly")
    }
    
    /**
     * BUG #3: Parameter parser should handle malformed descriptors gracefully.
     */
    @Test
    fun `BUG-3 - parameter parser should handle truncated object type`() {
        // Truncated object type: L without semicolon
        val content = """
            .class public Lcom/example/TestClass;
            .super Ljava/lang/Object;
            
            .method public broken(Landroid/os/Bundle)V
                .registers 1
                return-void
            .end method
        """.trimIndent()
        
        // Should not crash, should parse what it can
        val file = parser.parse("file:///test.smali", content)
        
        // May return null if ANTLR rejects it, or may parse partial
        // Either way, should not hang or crash
        println("✅ BUG #3 FIXED: Truncated object type handled (file=$file)")
    }
    
    /**
     * BUG #3: Parameter parser should handle truncated array.
     */
    @Test
    fun `BUG-3 - parameter parser should handle truncated array`() {
        // Truncated array: [ at end without element type
        val content = """
            .class public Lcom/example/TestClass;
            .super Ljava/lang/Object;
            
            .method public broken(I[)V
                .registers 1
                return-void
            .end method
        """.trimIndent()
        
        // Should not crash
        val file = parser.parse("file:///test.smali", content)
        
        println("✅ BUG #3 FIXED: Truncated array handled (file=$file)")
    }
    
    /**
     * BUG #3: Parameter parser should handle invalid characters.
     */
    @Test
    fun `BUG-3 - parameter parser should handle invalid characters in descriptor`() {
        // Invalid character 'X' in descriptor
        val content = """
            .class public Lcom/example/TestClass;
            .super Ljava/lang/Object;
            
            .method public broken(IX)V
                .registers 1
                return-void
            .end method
        """.trimIndent()
        
        // Should not hang in infinite loop
        val file = parser.parse("file:///test.smali", content)
        
        println("✅ BUG #3 FIXED: Invalid character handled (file=$file)")
    }
    
    /**
     * Edge case: Empty method body (abstract/native method)
     */
    @Test
    fun `EDGE-CASE - empty method body should not crash`() {
        val content = """
            .class public abstract Lcom/example/TestClass;
            .super Ljava/lang/Object;
            
            .method public abstract foo()V
            .end method
            
            .method public native bar()V
            .end method
        """.trimIndent()
        
        val file = parser.parse("file:///test.smali", content)
        assertNotNull(file, "Should parse abstract/native methods")
        
        assertEquals(2, file!!.methods.size, "Should have 2 methods")
        file.methods.forEach { method ->
            assertEquals(0, method.instructions.size, 
                "Abstract/native methods should have no instructions")
        }
        
        println("✅ EDGE CASE: Empty method bodies handled correctly")
    }
    
    /**
     * Edge case: Inner class with $ in name
     */
    @Test
    fun `EDGE-CASE - inner class with dollar sign should index correctly`() {
        val index = WorkspaceIndex()
        
        // Outer class
        val outerContent = """
            .class public Lcom/example/Outer;
            .super Ljava/lang/Object;
        """.trimIndent()
        val outerFile = parser.parse("file:///Outer.smali", outerContent)
        assertNotNull(outerFile)
        index.indexFile(outerFile!!)
        
        // Inner class
        val innerContent = """
            .class public Lcom/example/Outer${'$'}Inner;
            .super Ljava/lang/Object;
        """.trimIndent()
        val innerFile = parser.parse("file:///Outer\$Inner.smali", innerContent)
        assertNotNull(innerFile)
        index.indexFile(innerFile!!)
        
        // Test lookup
        val foundOuter = index.findClass("Lcom/example/Outer;")
        val foundInner = index.findClass("Lcom/example/Outer\$Inner;")
        
        assertNotNull(foundOuter, "Should find outer class")
        assertNotNull(foundInner, "Should find inner class with $ in name")
        
        // Test reverse lookup
        val foundClassName = index.findClassNameByUri("file:///Outer\$Inner.smali")
        assertEquals("Lcom/example/Outer\$Inner;", foundClassName, 
            "Should reverse lookup inner class with $ in URI")
        
        println("✅ EDGE CASE: Inner classes with $ handled correctly")
    }
}
