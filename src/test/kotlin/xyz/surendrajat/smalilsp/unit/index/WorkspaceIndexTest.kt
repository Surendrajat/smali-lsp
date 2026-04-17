package xyz.surendrajat.smalilsp.unit.index

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import xyz.surendrajat.smalilsp.core.*

import xyz.surendrajat.smalilsp.core.ClassDefinition
import xyz.surendrajat.smalilsp.core.SmaliFile
import xyz.surendrajat.smalilsp.index.WorkspaceIndex
/**
 * Test WorkspaceIndex with realistic scenarios.
 * 
 * These tests verify the index works correctly for:
 * - Basic indexing
 * - Cross-file lookups
 * - Method overloads
 * - Reverse dependencies
 * - Thread safety
 */
class WorkspaceIndexTest {
    
    private lateinit var index: WorkspaceIndex
    
    @BeforeEach
    fun setup() {
        index = WorkspaceIndex()
    }
    
    @Test
    fun `index simple class and find it`() {
        val file = createTestFile(
            className = "Lcom/example/Test;",
            uri = "file:///test/Test.smali"
        )
        
        index.indexFile(file)
        
        val found = index.findClass("Lcom/example/Test;")
        assertNotNull(found)
        assertEquals("Lcom/example/Test;", found?.classDefinition?.name)
    }
    
    @Test
    fun `find method in indexed class`() {
        val file = createTestFile(
            className = "Lcom/example/Test;",
            uri = "file:///test/Test.smali",
            methods = listOf(
                MethodDefinition(
                    name = "myMethod",
                    descriptor = "(I)V",
                    range = range(10, 0, 15, 0),
                    modifiers = setOf("public"),
                    parameters = listOf(Parameter("I", null)),
                    returnType = "V"
                )
            )
        )
        
        index.indexFile(file)
        
        val locations = index.findMethod("Lcom/example/Test;", "myMethod", "(I)V")
        assertEquals(1, locations.size)
        val location = locations.first()
        assertEquals("file:///test/Test.smali", location.uri)
        assertEquals(10, location.range.start.line)
    }
    
    @Test
    fun `find field in indexed class`() {
        val file = createTestFile(
            className = "Lcom/example/Test;",
            uri = "file:///test/Test.smali",
            fields = listOf(
                FieldDefinition(
                    name = "myField",
                    type = "I",
                    range = range(5, 0, 5, 20),
                    modifiers = setOf("private")
                )
            )
        )
        
        index.indexFile(file)
        
        val location = index.findField("Lcom/example/Test;", "myField")
        assertNotNull(location)
        assertEquals("file:///test/Test.smali", location?.uri)
        assertEquals(5, location?.range?.start?.line)
    }
    
    @Test
    fun `track class usages for inheritance`() {
        val baseClass = createTestFile(
            className = "Lcom/example/Base;",
            uri = "file:///test/Base.smali"
        )
        
        val derivedClass = createTestFile(
            className = "Lcom/example/Derived;",
            uri = "file:///test/Derived.smali",
            superClass = "Lcom/example/Base;"
        )
        
        index.indexFile(baseClass)
        index.indexFile(derivedClass)
        
        val usages = index.findClassUsages("Lcom/example/Base;")
        assertEquals(1, usages.size)
        assertTrue(usages.contains("file:///test/Derived.smali"))
    }
    
    @Test
    fun `track class usages for interfaces`() {
        val interfaceClass = createTestFile(
            className = "Lcom/example/IFace;",
            uri = "file:///test/IFace.smali"
        )
        
        val implClass = createTestFile(
            className = "Lcom/example/Impl;",
            uri = "file:///test/Impl.smali",
            interfaces = listOf("Lcom/example/IFace;")
        )
        
        index.indexFile(interfaceClass)
        index.indexFile(implClass)
        
        val usages = index.findClassUsages("Lcom/example/IFace;")
        assertEquals(1, usages.size)
        assertTrue(usages.contains("file:///test/Impl.smali"))
    }
    
    @Test
    fun `handle method overloads - multiple definitions with same name`() {
        val file = createTestFile(
            className = "Lcom/example/Test;",
            uri = "file:///test/Test.smali",
            methods = listOf(
                MethodDefinition(
                    name = "foo",
                    descriptor = "(I)V",
                    range = range(10, 0, 15, 0),
                    modifiers = setOf("public"),
                    parameters = listOf(Parameter("I", null)),
                    returnType = "V"
                ),
                MethodDefinition(
                    name = "foo",
                    descriptor = "(Ljava/lang/String;)V",
                    range = range(20, 0, 25, 0),
                    modifiers = setOf("public"),
                    parameters = listOf(Parameter("Ljava/lang/String;", null)),
                    returnType = "V"
                )
            )
        )
        
        index.indexFile(file)
        
        // Find first overload
        val locations1 = index.findMethod("Lcom/example/Test;", "foo", "(I)V")
        assertEquals(1, locations1.size)
        assertEquals(10, locations1.first().range.start.line)
        
        // Find second overload
        val locations2 = index.findMethod("Lcom/example/Test;", "foo", "(Ljava/lang/String;)V")
        assertEquals(1, locations2.size)
        assertEquals(20, locations2.first().range.start.line)
    }
    
    @Test
    fun `get index statistics`() {
        index.indexFile(createTestFile(
            className = "Lcom/example/A;",
            uri = "file:///A.smali",
            methods = listOf(createTestMethod("m1"), createTestMethod("m2")),
            fields = listOf(createTestField("f1"))
        ))
        
        index.indexFile(createTestFile(
            className = "Lcom/example/B;",
            uri = "file:///B.smali",
            methods = listOf(createTestMethod("m3")),
            fields = listOf(createTestField("f2"), createTestField("f3"))
        ))
        
        val stats = index.getStats()
        assertEquals(2, stats.classes)
        assertEquals(3, stats.methods)
        assertEquals(3, stats.fields)
    }
    
    @Test
    fun `return empty set for non-existent class`() {
        val found = index.findClass("Lcom/example/NonExistent;")
        assertNull(found)
    }
    
    @Test
    fun `return empty set for non-existent method`() {
        val locations = index.findMethod("Lcom/example/Test;", "nonExistent", "()V")
        assertTrue(locations.isEmpty())
    }

    @Test
    fun `removeFile clears class entry and reverse references`() {
        val uri = "file:///test/Deleted.smali"
        val className = "Lcom/example/Deleted;"
        val file = createTestFile(
            className = className,
            uri = uri,
            methods = listOf(createTestMethod("foo")),
            fields = listOf(createTestField("bar"))
        )

        // Another file references the class we're about to delete
        val referrerUri = "file:///test/Referrer.smali"
        val referrer = SmaliFile(
            uri = referrerUri,
            classDefinition = ClassDefinition(
                name = "Lcom/example/Referrer;",
                range = range(0, 0, 1, 0),
                modifiers = setOf("public"),
                superClass = className,
                interfaces = emptyList()
            ),
            methods = emptyList(),
            fields = emptyList()
        )

        index.indexFile(file)
        index.indexFile(referrer)

        assertNotNull(index.findClass(className))
        assertTrue(index.getDirectSubclasses(className).contains("Lcom/example/Referrer;"))

        val removed = index.removeFile(uri)
        assertTrue(removed, "removeFile should return true for an indexed file")

        // Class entry gone
        assertNull(index.findClass(className))
        // Reverse lookup gone
        assertNull(index.findFileByUri(uri))
        // Method/field entries gone
        assertTrue(index.findMethod(className, "foo", "()V").isEmpty())
        assertNull(index.findField(className, "bar"))
        // Subclass hierarchy still has referrer but no parent entry
        assertTrue(index.getDirectSubclasses(className).contains("Lcom/example/Referrer;"))
    }

    @Test
    fun `removeFile returns false for unindexed uri`() {
        assertFalse(index.removeFile("file:///never-indexed.smali"))
    }

    // Helper methods to create test data
    
    private fun createTestFile(
        className: String,
        uri: String,
        superClass: String? = "Ljava/lang/Object;",
        interfaces: List<String> = emptyList(),
        methods: List<MethodDefinition> = emptyList(),
        fields: List<FieldDefinition> = emptyList()
    ): SmaliFile {
        return SmaliFile(
            uri = uri,
            classDefinition = ClassDefinition(
                name = className,
                range = range(0, 0, 1, 0),
                modifiers = setOf("public"),
                superClass = superClass,
                interfaces = interfaces
            ),
            methods = methods,
            fields = fields
        )
    }
    
    private fun createTestMethod(name: String): MethodDefinition {
        return MethodDefinition(
            name = name,
            descriptor = "()V",
            range = range(0, 0, 1, 0),
            modifiers = setOf("public"),
            parameters = emptyList(),
            returnType = "V"
        )
    }
    
    private fun createTestField(name: String): FieldDefinition {
        return FieldDefinition(
            name = name,
            type = "I",
            range = range(0, 0, 0, 10),
            modifiers = setOf("private")
        )
    }
}
