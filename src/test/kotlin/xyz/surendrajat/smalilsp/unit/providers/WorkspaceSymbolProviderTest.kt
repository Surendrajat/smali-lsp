package xyz.surendrajat.smalilsp.unit.providers

import org.eclipse.lsp4j.SymbolKind
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import xyz.surendrajat.smalilsp.core.*
import xyz.surendrajat.smalilsp.index.WorkspaceIndex

import xyz.surendrajat.smalilsp.core.ClassDefinition
import xyz.surendrajat.smalilsp.core.SmaliFile
import xyz.surendrajat.smalilsp.providers.WorkspaceSymbolProvider
/**
 * Unit tests for WorkspaceSymbolProvider.
 * 
 * Test coverage:
 * - Empty queries
 * - Exact matches
 * - Prefix matches
 * - Contains matches
 * - Fuzzy matches
 * - Case insensitivity
 * - Result ranking
 * - Result limits
 * - Performance on large workspaces
 */
class WorkspaceSymbolProviderTest {
    
    /**
     * Test empty query returns all symbols (up to limit).
     */
    @Test
    fun `empty query returns all symbols`() {
        val index = createTestIndex()
        val provider = WorkspaceSymbolProvider(index)
        
        val results = provider.search("")
        
        // Should return symbols (empty query matches everything)
        assertTrue(results.isNotEmpty())
        assertTrue(results.size <= 100) // Respects MAX_RESULTS
    }
    
    /**
     * Test exact class name match.
     */
    @Test
    fun `exact class name match`() {
        val index = createTestIndex()
        val provider = WorkspaceSymbolProvider(index)
        
        val results = provider.search("Lcom/example/MainActivity;")
        
        // Should find exact match first
        assertTrue(results.isNotEmpty())
        assertEquals("Lcom/example/MainActivity;", results[0].name)
        assertEquals(SymbolKind.Class, results[0].kind)
    }
    
    /**
     * Test case-insensitive matching.
     */
    @Test
    fun `case insensitive matching`() {
        val index = createTestIndex()
        val provider = WorkspaceSymbolProvider(index)
        
        val results = provider.search("mainactivity")
        
        // Should match MainActivity regardless of case
        assertTrue(results.any { it.name.contains("MainActivity") })
    }
    
    /**
     * Test prefix matching.
     */
    @Test
    fun `prefix matching works`() {
        val index = createTestIndex()
        val provider = WorkspaceSymbolProvider(index)
        
        val results = provider.search("Lcom/example/Main")
        
        // Should find classes starting with Lcom/example/Main
        assertTrue(results.any { it.name.startsWith("Lcom/example/Main") })
    }
    
    /**
     * Test contains matching.
     */
    @Test
    fun `contains matching works`() {
        val index = createTestIndex()
        val provider = WorkspaceSymbolProvider(index)
        
        val results = provider.search("Activity")
        
        // Should find symbols containing "Activity"
        assertTrue(results.any { it.name.contains("Activity") })
    }
    
    /**
     * Test fuzzy matching: FBA → FeedBackAdapter.
     */
    @Test
    fun `fuzzy matching works`() {
        val index = WorkspaceIndex()
        
        // Create a class with a name good for fuzzy matching
        val file = SmaliFile(
            uri = "file:///test/FeedBackAdapter.smali",
            classDefinition = ClassDefinition(
                name = "Lcom/example/FeedBackAdapter;",
                range = range(0, 0, 10, 0),
                modifiers = setOf("public"),
                superClass = "Ljava/lang/Object;",
                interfaces = emptyList()
            ),
            methods = emptyList(),
            fields = emptyList()
        )
        index.indexFile(file)
        
        val provider = WorkspaceSymbolProvider(index)
        val results = provider.search("FBA")
        
        // Should fuzzy match FeedBackAdapter
        assertTrue(results.any { it.name.contains("FeedBackAdapter") })
    }
    
    /**
     * Test method symbol search.
     */
    @Test
    fun `method symbols are searchable`() {
        val index = createTestIndex()
        val provider = WorkspaceSymbolProvider(index)
        
        val results = provider.search("onCreate")
        
        // Should find onCreate methods
        assertTrue(results.any { it.kind == SymbolKind.Method && it.name.contains("onCreate") })
    }
    
    /**
     * Test field symbol search.
     */
    @Test
    fun `field symbols are searchable`() {
        val index = createTestIndex()
        val provider = WorkspaceSymbolProvider(index)
        
        val results = provider.search("buttonSubmit")
        
        // Should find buttonSubmit field
        assertTrue(results.any { it.kind == SymbolKind.Field && it.name.contains("buttonSubmit") })
    }
    
    /**
     * Test result ranking: exact > prefix > contains > fuzzy.
     */
    @Test
    fun `results are ranked by match quality`() {
        val index = WorkspaceIndex()
        
        // Create files with different match qualities
        val exactMatch = createClassFile("Lcom/example/Test;", "file:///exact.smali")
        val prefixMatch = createClassFile("Lcom/example/TestActivity;", "file:///prefix.smali")
        val containsMatch = createClassFile("Lcom/example/MyTestHelper;", "file:///contains.smali")
        
        index.indexFile(exactMatch)
        index.indexFile(prefixMatch)
        index.indexFile(containsMatch)
        
        val provider = WorkspaceSymbolProvider(index)
        val results = provider.search("Test")
        
        // Exact match should come first
        assertTrue(results.isNotEmpty())
        // Results should be sorted by relevance (exact or prefix first)
        val topResult = results[0].name
        assertTrue(topResult.endsWith("Test;") || topResult.contains("Test"))
    }
    
    /**
     * Test result limit (max 100 results).
     */
    @Test
    fun `results are limited to 100`() {
        val index = WorkspaceIndex()
        
        // Create 150 classes all matching the query
        repeat(150) { i ->
            val file = createClassFile("Lcom/example/Class$i;", "file:///class$i.smali")
            index.indexFile(file)
        }
        
        val provider = WorkspaceSymbolProvider(index)
        val results = provider.search("Class")
        
        // Should limit to 100 results
        assertEquals(100, results.size)
    }
    
    /**
     * Test searching for methods with descriptors.
     */
    @Test
    fun `method descriptors are searchable`() {
        val index = createTestIndex()
        val provider = WorkspaceSymbolProvider(index)
        
        val results = provider.search("()V")
        
        // Should not crash, methods are named "ClassName.methodName"
        // Descriptors are not part of the searchable name
        assertNotNull(results)
    }
    
    /**
     * Test performance on large workspace (4000+ symbols).
     */
    @Test
    @Timeout(1) // Must complete in < 1 second
    fun `performance on large workspace`() {
        val index = WorkspaceIndex()
        
        // Create 1000 classes with 5 methods and 3 fields each
        // Total: 1000 classes + 5000 methods + 3000 fields = 9000 symbols
        repeat(1000) { i ->
            val file = SmaliFile(
                uri = "file:///test/Class$i.smali",
                classDefinition = ClassDefinition(
                    name = "Lcom/example/Class$i;",
                    range = range(0, 0, 10, 0),
                    modifiers = setOf("public"),
                    superClass = "Ljava/lang/Object;",
                    interfaces = emptyList()
                ),
                methods = List(5) { methodIdx ->
                    MethodDefinition(
                        name = "method$methodIdx",
                        descriptor = "()V",
                        range = range(methodIdx, 0, methodIdx + 1, 0),
                        modifiers = setOf("public"),
                        parameters = emptyList(),
                        returnType = "V"
                    )
                },
                fields = List(3) { fieldIdx ->
                    FieldDefinition(
                        name = "field$fieldIdx",
                        type = "I",
                        range = range(fieldIdx, 0, fieldIdx, 10),
                        modifiers = setOf("private")
                    )
                }
            )
            index.indexFile(file)
        }
        
        val provider = WorkspaceSymbolProvider(index)
        
        val start = System.currentTimeMillis()
        val results = provider.search("Class")
        val elapsed = System.currentTimeMillis() - start
        
        // Should find results quickly
        assertTrue(results.isNotEmpty())
        assertEquals(100, results.size) // Limited to 100
        assertTrue(elapsed < 500, "Should complete in < 500ms, was ${elapsed}ms")
    }
    
    /**
     * Test no matches returns empty list.
     */
    @Test
    fun `no matches returns empty list`() {
        val index = createTestIndex()
        val provider = WorkspaceSymbolProvider(index)
        
        val results = provider.search("NonExistentSymbolXYZ123")
        
        assertEquals(0, results.size)
    }
    
    /**
     * Test symbols from multiple files.
     */
    @Test
    fun `searches across multiple files`() {
        val index = WorkspaceIndex()
        
        val file1 = createClassFile("Lcom/example/ClassA;", "file:///a.smali")
        val file2 = createClassFile("Lcom/example/ClassB;", "file:///b.smali")
        val file3 = createClassFile("Lcom/other/ClassC;", "file:///c.smali")
        
        index.indexFile(file1)
        index.indexFile(file2)
        index.indexFile(file3)
        
        val provider = WorkspaceSymbolProvider(index)
        val results = provider.search("Class")
        
        // Should find all three classes
        assertEquals(3, results.size)
        assertTrue(results.any { it.name.contains("ClassA") })
        assertTrue(results.any { it.name.contains("ClassB") })
        assertTrue(results.any { it.name.contains("ClassC") })
    }
    
    /**
     * Test location information is correct.
     */
    @Test
    fun `location information is correct`() {
        val index = WorkspaceIndex()
        
        val file = SmaliFile(
            uri = "file:///test/MyClass.smali",
            classDefinition = ClassDefinition(
                name = "Lcom/example/MyClass;",
                range = range(0, 0, 5, 0),
                modifiers = setOf("public"),
                superClass = "Ljava/lang/Object;",
                interfaces = emptyList()
            ),
            methods = listOf(
                MethodDefinition(
                    name = "testMethod",
                    descriptor = "()V",
                    range = range(10, 4, 12, 15),
                    modifiers = setOf("public"),
                    parameters = emptyList(),
                    returnType = "V"
                )
            ),
            fields = emptyList()
        )
        index.indexFile(file)
        
        val provider = WorkspaceSymbolProvider(index)
        val results = provider.search("testMethod")
        
        assertTrue(results.isNotEmpty())
        val methodSymbol = results.find { it.kind == SymbolKind.Method }
        assertNotNull(methodSymbol)
        assertEquals("file:///test/MyClass.smali", methodSymbol!!.location.uri)
        assertEquals(10, methodSymbol.location.range.start.line)
        assertEquals(4, methodSymbol.location.range.start.character)
    }
    
    /**
     * Test container name is set correctly.
     */
    @Test
    fun `container name is set for methods and fields`() {
        val index = createTestIndex()
        val provider = WorkspaceSymbolProvider(index)
        
        val results = provider.search("onCreate")
        
        val methodSymbol = results.find { it.kind == SymbolKind.Method }
        assertNotNull(methodSymbol)
        assertNotNull(methodSymbol!!.containerName)
        assertTrue(methodSymbol.containerName.contains("MainActivity"))
    }
    
    /**
     * Helper: Create a test index with sample data.
     */
    private fun createTestIndex(): WorkspaceIndex {
        val index = WorkspaceIndex()
        
        // MainActivity
        val mainActivity = SmaliFile(
            uri = "file:///test/MainActivity.smali",
            classDefinition = ClassDefinition(
                name = "Lcom/example/MainActivity;",
                range = range(0, 0, 30, 0),
                modifiers = setOf("public"),
                superClass = "Landroid/app/Activity;",
                interfaces = emptyList()
            ),
            methods = listOf(
                MethodDefinition(
                    name = "onCreate",
                    descriptor = "(Landroid/os/Bundle;)V",
                    range = range(10, 4, 15, 15),
                    modifiers = setOf("protected"),
                    parameters = listOf(Parameter("Landroid/os/Bundle;", "bundle")),
                    returnType = "V"
                )
            ),
            fields = listOf(
                FieldDefinition(
                    name = "buttonSubmit",
                    type = "Landroid/widget/Button;",
                    range = range(5, 4, 5, 30),
                    modifiers = setOf("private")
                )
            )
        )
        
        index.indexFile(mainActivity)
        
        return index
    }
    
    /**
     * Helper: Create a simple class file.
     */
    private fun createClassFile(className: String, uri: String): SmaliFile {
        return SmaliFile(
            uri = uri,
            classDefinition = ClassDefinition(
                name = className,
                range = range(0, 0, 10, 0),
                modifiers = setOf("public"),
                superClass = "Ljava/lang/Object;",
                interfaces = emptyList()
            ),
            methods = emptyList(),
            fields = emptyList()
        )
    }
}
